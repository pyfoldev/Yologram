"""Кастомные эмодзи из пака (берутся из Telegram при старте по getStickerSet)."""
from __future__ import annotations

import json
import logging
from pathlib import Path

log = logging.getLogger("emojis")
OVERRIDE = Path(__file__).with_name("emoji_map.json")  # {"id": "5368...", ...} — ручная привязка

# ключ -> (запасной обычный эмодзи, эмодзи-символы пака, которыми он может быть помечен)
SLOTS = {
    "id": ("🆔", ["🆔", "🔢", "#️⃣"]),
    "user": ("👤", ["👤", "🙂", "😀"]),
    "group": ("👥", ["👥", "💬"]),
    "channel": ("📢", ["📢", "📣"]),
    "date": ("📅", ["📅", "📆", "🗓", "⏰", "🕐"]),
    "link": ("🔗", ["🔗", "🌐"]),
    "contact": ("📇", ["📇", "📞", "☎️", "📱"]),
    "search": ("🔍", ["🔍", "🔎"]),
    "ok": ("✅", ["✅", "👍", "☑️"]),
    "warn": ("⚠️", ["⚠️", "❗", "❌", "🚫"]),
    "back": ("◀️", ["◀️", "⬅️", "🔙"]),
    "me": ("🪪", ["🪪", "😎", "⭐"]),
    "star": ("⭐", ["⭐", "🌟", "💎"]),
}

_by_emoji: dict[str, str] = {}
_manual: dict[str, str] = {}


async def load(bot, pack: str) -> int:
    global _manual
    try:
        _manual = json.loads(OVERRIDE.read_text())
    except (OSError, ValueError):
        _manual = {}
    try:
        st = await bot.get_sticker_set(pack)
    except Exception as e:  # noqa: BLE001
        log.warning("Не удалось загрузить пак %s: %s", pack, e)
        return 0
    for s in st.stickers:
        if s.custom_emoji_id and s.emoji:
            _by_emoji.setdefault(s.emoji, s.custom_emoji_id)
            _by_emoji.setdefault(s.emoji.replace("️", ""), s.custom_emoji_id)
    log.info("Пак %s: %d эмодзи", pack, len(st.stickers))
    return len(st.stickers)


def cid(key: str) -> str | None:
    if key in _manual:
        return _manual[key]
    for ch in SLOTS[key][1]:
        if ch in _by_emoji:
            return _by_emoji[ch]
    return None


def e(key: str, fancy: bool = True) -> str:
    """HTML-эмодзи для текста."""
    fb = SLOTS[key][0]
    c = cid(key) if fancy else None
    return f'<tg-emoji emoji-id="{c}">{fb}</tg-emoji>' if c else fb


def dump() -> dict[str, str]:
    return dict(_by_emoji)
