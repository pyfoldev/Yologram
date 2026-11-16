from __future__ import annotations

import ast
import html
import operator
import re
import time
from io import BytesIO

from aiogram import Bot
from aiogram.exceptions import TelegramBadRequest
from aiogram.types import BufferedInputFile

esc = html.escape

_UNITS = {"s": 1, "с": 1, "m": 60, "м": 60, "h": 3600, "ч": 3600, "d": 86400, "д": 86400}
_DUR = re.compile(r"^(\d{1,5})\s*([a-zа-я]?)$", re.IGNORECASE)


def parse_duration(s: str) -> int | None:
    """«30м», «2h», «1д», «45» (минуты) → секунды; None, если не длительность."""
    m = _DUR.match(s.strip().lower())
    if not m:
        return None
    n, unit = int(m.group(1)), m.group(2) or "m"
    if unit not in _UNITS or n <= 0:
        return None
    return n * _UNITS[unit]


def fmt_duration(sec: int) -> str:
    sec = int(sec)
    d, rest = divmod(sec, 86400)
    h, rest = divmod(rest, 3600)
    m = rest // 60
    parts = [f"{d} д" if d else "", f"{h} ч" if h else "", f"{m} мин" if m else ""]
    out = " ".join(p for p in parts if p)
    return out or f"{sec} с"


# --- безопасный калькулятор ---
_OPS = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv,
        ast.FloorDiv: operator.floordiv, ast.Mod: operator.mod, ast.Pow: operator.pow}


def _ev(node):
    if isinstance(node, ast.Expression):
        return _ev(node.body)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)) and not isinstance(node.value, bool):
        return node.value
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
        v = _ev(node.operand)
        return v if isinstance(node.op, ast.UAdd) else -v
    if isinstance(node, ast.BinOp) and type(node.op) in _OPS:
        a, b = _ev(node.left), _ev(node.right)
        if isinstance(node.op, ast.Pow) and (abs(b) > 1000 or abs(a) > 1e6):
            raise ValueError("слишком большая степень")
        return _OPS[type(node.op)](a, b)
    raise ValueError("недопустимое выражение")


def calc(expr: str) -> str:
    expr = expr.replace("×", "*").replace("÷", "/").replace("^", "**").replace(",", ".").replace(":", "/")
    if len(expr) > 100:
        raise ValueError("слишком длинно")
    res = _ev(ast.parse(expr.strip(), mode="eval"))
    if isinstance(res, float):
        res = round(res, 10)
        if res == int(res) and abs(res) < 1e15:
            res = int(res)
    return str(res)


def mock(text: str) -> str:
    out, up = [], False
    for ch in text:
        if ch.isalpha():
            out.append(ch.upper() if up else ch.lower())
            up = not up
        else:
            out.append(ch)
    return "".join(out)


def local_minutes(tz: int) -> int:
    t = time.gmtime(time.time() + tz * 3600)
    return t.tm_hour * 60 + t.tm_min


def parse_hhmm(s: str) -> int | None:
    m = re.match(r"^(\d{1,2})[:.](\d{2})$", s.strip())
    if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
        return None
    return int(m.group(1)) * 60 + int(m.group(2))


def in_window(now_min: int, start: int, end: int) -> bool:
    """Попадает ли время в окно [start, end); окно может переходить через полночь (22:00–09:00)."""
    if start == end:
        return True
    return start <= now_min < end if start < end else now_min >= start or now_min < end


# --- вложения ---
def extract_media(m) -> tuple[str | None, str | None]:
    if m.photo:
        return "photo", m.photo[-1].file_id
    for kind in ("video", "video_note", "voice", "audio", "animation", "document", "sticker"):
        obj = getattr(m, kind, None)
        if obj:
            return kind, obj.file_id
    return None, None


KIND_TITLES = {"photo": "📷 Фото", "video": "🎬 Видео", "video_note": "⭕ Кружок", "voice": "🎤 Голосовое",
               "audio": "🎵 Аудио", "animation": "🎞 GIF", "document": "📎 Файл", "sticker": "🏷 Стикер"}
_NO_CAPTION = {"video_note", "sticker"}
_EXT = {"photo": "jpg", "video": "mp4", "video_note": "mp4", "voice": "ogg", "audio": "mp3", "animation": "mp4",
        "document": "bin", "sticker": "webp"}


async def send_media(bot: Bot, chat_id: int, kind: str, file_id: str, caption: str | None = None) -> None:
    send = getattr(bot, f"send_{kind}")
    extra = {} if kind in _NO_CAPTION or not caption else {"caption": caption}
    try:
        await send(chat_id, file_id, **extra)
    except TelegramBadRequest:
        # file_id из бизнес-чата иногда не принимается напрямую — скачиваем и загружаем заново
        buf = BytesIO()
        await bot.download(file_id, destination=buf)
        await send(chat_id, BufferedInputFile(buf.getvalue(), filename=f"nori.{_EXT.get(kind, 'bin')}"), **extra)
    if caption and kind in _NO_CAPTION:
        await bot.send_message(chat_id, caption)


def user_link(uid: int, name: str) -> str:
    return f'<a href="tg://user?id={uid}">{esc(name)}</a>'
