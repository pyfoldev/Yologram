"""Автозамена обычных эмодзи на премиум в исходящих сообщениях и кнопках.

Карта берётся из bot/emoji_map.json (строится tools/build_emoji_map.py из паков), плюс ALIASES — близкие по смыслу
замены для эмодзи, которых в паках нет. Если Telegram отклонит запрос с заменами, сессия повторит его без замен.
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
from pathlib import Path

from aiogram.client.session.aiohttp import AiohttpSession
from aiogram.exceptions import TelegramBadRequest, TelegramRetryAfter
from aiogram.methods import (EditMessageCaption, EditMessageReplyMarkup, EditMessageText, SendDocument, SendMessage,
                            SendPhoto)
from aiogram.types import InlineKeyboardMarkup

VS16 = "️"
ZWJ = "‍"
KEYCAP = "⃣"
MAX_PER_MESSAGE = 40
_MAP_FILE = Path(__file__).with_name("emoji_map.json")

# эмодзи, которых нет в паках → эмодзи из паков, близкий по смыслу
ALIASES = {
    "🔨": "🚫", "👢": "⛔", "🎰": "🎁", "⚔": "🔥", "💍": "❤", "💞": "❤", "💔": "❌", "😘": "❤", "🤗": "❤",
    "🥰": "❤", "💖": "❤", "👶": "👤", "🎲": "🎯", "🎭": "🎨", "🤝": "👥", "🔮": "✨", "🕰": "⏱",
    "🧹": "🗑", "📝": "✏", "🗳": "📥", "⚖": "🛡", "🔫": "⚡", "🥳": "🎁", "🎉": "🎁",
}


def norm(e: str) -> str:
    return e.replace(VS16, "")


def load_map() -> dict[str, str]:
    try:
        base = json.loads(_MAP_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    for char, like in ALIASES.items():
        if char not in base and like in base:
            base[char] = base[like]
    return base


def _emoji_at(text: str, i: int, emap: dict) -> tuple[str, str, int] | None:
    """(исходный фрагмент, id, длина) если в позиции i стоит известный одиночный эмодзи, иначе None."""
    c = text[i]
    j = i + 1
    if c in "0123456789#*":  # keycap: цифра [FE0F] 20E3
        if j < len(text) and text[j] == VS16:
            j += 1
        if j < len(text) and text[j] == KEYCAP:
            key = c + KEYCAP
            return (text[i:j + 1], emap[key], j + 1 - i) if key in emap else None
        return None
    if ord(c) < 0x2000 and c not in "©®":
        return None
    if j < len(text) and text[j] == VS16:
        j += 1
    # составные последовательности (ZWJ, тон кожи) не трогаем целиком — не режем их на части
    if j < len(text) and (text[j] == ZWJ or "\U0001f3fb" <= text[j] <= "\U0001f3ff"):
        return None
    if i > 0 and text[i - 1] == ZWJ:
        return None
    cid = emap.get(norm(c))
    return (text[i:j], cid, j - i) if cid else None


def _inject_text(text: str, emap: dict, budget: list[int]) -> str:
    out, i = [], 0
    while i < len(text):
        hit = _emoji_at(text, i, emap) if budget[0] > 0 else None
        if hit:
            frag, cid, n = hit
            out.append(f'<tg-emoji emoji-id="{cid}">{frag}</tg-emoji>')
            budget[0] -= 1
            i += n
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


_TAG_RE = re.compile(r"(<[^>]*>)")
_SKIP = {"code", "pre", "tg-emoji"}


def inject_html(html_text: str, emap: dict) -> str:
    """Заменяет эмодзи только в текстовых узлах; внутри <code>, <pre> и уже готовых <tg-emoji> не трогает."""
    if not emap or not html_text:
        return html_text
    budget = [MAX_PER_MESSAGE]
    depth = 0
    parts = []
    for tok in _TAG_RE.split(html_text):
        if tok.startswith("<") and tok.endswith(">"):
            m = re.match(r"<(/?)([a-zA-Z-]+)", tok)
            if m and m.group(2).lower() in _SKIP:
                depth += -1 if m.group(1) else 1
                depth = max(depth, 0)
            parts.append(tok)
        else:
            parts.append(tok if depth else _inject_text(tok, emap, budget))
    return "".join(parts)


def inject_markup(markup, emap: dict):
    """Ведущий эмодзи кнопки → icon_custom_emoji_id (текст без него). Остальные кнопки без изменений."""
    if not isinstance(markup, InlineKeyboardMarkup) or not emap:
        return markup
    rows = []
    for row in markup.inline_keyboard:
        new_row = []
        for b in row:
            hit = _emoji_at(b.text, 0, emap) if b.text and not b.icon_custom_emoji_id else None
            if hit:
                frag, cid, n = hit
                rest = b.text[n:].strip()
                if rest:  # кнопка только из эмодзи остаётся как есть
                    b = b.model_copy(update={"text": rest, "icon_custom_emoji_id": cid})
            new_row.append(b)
        rows.append(new_row)
    return InlineKeyboardMarkup(inline_keyboard=rows)


def _html_mode(method) -> bool:
    pm = method.parse_mode
    if pm is None:
        return False
    # Default("parse_mode") разворачивается позже, в нашем боте по умолчанию HTML
    return str(getattr(pm, "name", pm)).lower() in ("parse_mode", "html")


def transform_method(method, emap: dict):
    """Копия метода с автозаменой; None — если менять нечего."""
    if isinstance(method, (SendMessage, EditMessageText)):
        upd = {}
        if _html_mode(method) and method.text:
            new = inject_html(method.text, emap)
            if new != method.text:
                upd["text"] = new
        mk = inject_markup(method.reply_markup, emap)
        if mk is not method.reply_markup and mk != method.reply_markup:
            upd["reply_markup"] = mk
        return method.model_copy(update=upd) if upd else None
    if isinstance(method, (SendDocument, SendPhoto, EditMessageCaption)):   # подписи к файлам и правка подписи
        upd = {}
        if _html_mode(method) and method.caption:
            new = inject_html(method.caption, emap)
            if new != method.caption:
                upd["caption"] = new
        mk = inject_markup(method.reply_markup, emap)
        if mk is not method.reply_markup and mk != method.reply_markup:
            upd["reply_markup"] = mk
        return method.model_copy(update=upd) if upd else None
    if isinstance(method, EditMessageReplyMarkup):   # кнопки меняют отдельным запросом (например, «Отменить» → пусто)
        mk = inject_markup(method.reply_markup, emap)
        return method.model_copy(update={"reply_markup": mk}) if mk is not method.reply_markup and mk != method.reply_markup else None
    return None


class EmojiSession(AiohttpSession):
    """HTTP-сессия бота: подменяет эмодзи на премиум; при отказе Telegram отправляет исходный текст."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.emap = load_map()

    FLOOD_RETRIES = 3        # сколько раз ждать и повторять при «слишком часто» от Telegram
    FLOOD_MAX_WAIT = 20.0    # дольше этого не ждём: обработчик не должен висеть минутами
    flood_waits = 0          # сколько раз упирались в лимит (для метрик)

    async def _raw(self, bot, method, timeout):
        return await super().make_request(bot, method, timeout)

    async def _send(self, bot, method, timeout):
        """Telegram режет частоту (≈20 сообщений/мин на группу, массовые удаления/баны тоже): вместо потери действия
        во время рейда ждём указанное сервером время и повторяем."""
        for attempt in range(self.FLOOD_RETRIES + 1):
            try:
                return await self._raw(bot, method, timeout)
            except TelegramRetryAfter as e:
                self.flood_waits += 1
                if attempt == self.FLOOD_RETRIES or e.retry_after > self.FLOOD_MAX_WAIT:
                    raise
                logging.warning("flood control: %s, жду %s с (попытка %s)", type(method).__name__, e.retry_after, attempt + 1)
                await asyncio.sleep(e.retry_after + 0.2)

    async def make_request(self, bot, method, timeout=None):
        changed = transform_method(method, self.emap)
        if changed is None:
            return await self._send(bot, method, timeout)
        try:
            return await self._send(bot, changed, timeout)
        except TelegramBadRequest as e:
            logging.warning("premium emoji rejected, sending plain: %s", str(e)[:120])
            return await self._send(bot, method, timeout)  # премиум-замена не принята — шлём как было
