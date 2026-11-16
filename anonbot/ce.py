"""Кастомные эмодзи из пака https://t.me/addemoji/solutions_project_by_Solutmark_bot

При старте бот читает набор через getStickerSet и строит карту «обычный эмодзи -> custom_emoji_id».
Затем middleware заменяет обычные эмодзи в исходящих текстах (send/edit message) на кастомные.
Если Telegram не разрешает боту кастомные эмодзи, клиент покажет обычный эмодзи — ничего не ломается.
"""
import logging
import os
import re

from aiogram import Bot
from aiogram.methods import EditMessageText, SendMessage
from aiogram.types import MessageEntity

log = logging.getLogger("anonbot.ce")
PACK = os.environ.get("EMOJI_PACK", "solutions_project_by_Solutmark_bot")
_map: dict[str, str] = {}
_re = None


def norm(s: str) -> str:
    return s.replace("️", "")


def build(pairs):
    """pairs: iterable[(emoji, custom_emoji_id)]; для повтора берётся первый."""
    global _re
    _map.clear()
    for e, cid in pairs:
        _map.setdefault(norm(e), cid)
    keys = sorted(_map, key=len, reverse=True)
    _re = re.compile("(?:" + "|".join(map(re.escape, keys)) + ")️?") if keys else None


def decorate(text: str):
    """Возвращает список MessageEntity custom_emoji (offset/length в UTF-16)."""
    if not _re or not text:
        return None
    out = []
    for m in _re.finditer(text):
        off = len(text[:m.start()].encode("utf-16-le")) // 2
        ln = len(m.group().encode("utf-16-le")) // 2
        out.append(MessageEntity(type="custom_emoji", offset=off, length=ln,
                                 custom_emoji_id=_map[norm(m.group())]))
    return out or None


async def _middleware(make_request, bot, method):
    if isinstance(method, (SendMessage, EditMessageText)) and method.text \
            and not method.entities and not isinstance(method.parse_mode, str):
        ents = decorate(method.text)
        if ents:
            method.entities = ents
    return await make_request(bot, method)


async def setup(bot: Bot):
    try:
        st = await bot.get_sticker_set(PACK)
        build((s.emoji, s.custom_emoji_id) for s in st.stickers if s.custom_emoji_id and s.emoji)
        log.info("Эмодзи-пак %s: %d эмодзи", PACK, len(_map))
    except Exception as e:  # пак недоступен — работаем с обычными эмодзи
        log.warning("Не удалось загрузить эмодзи-пак: %s", e)
        return
    bot.session.middleware(_middleware)
