"""Бот анонимных сообщений.

/start -> личная ссылка вида t.me/<bot>?start=<код>. Её ставят в профиль.
Любой, кто перешёл по ссылке, пишет сообщение — владельцу оно приходит анонимно.
Владелец может ответить (тоже анонимно) или заблокировать отправителя.
"""
import asyncio
import logging
import os
import secrets
import sqlite3

from aiogram import Bot, Dispatcher, F, Router
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramBadRequest, TelegramForbiddenError
from aiogram.filters import Command, CommandObject
from aiogram.types import (
    CallbackQuery,
    InlineKeyboardButton as Btn,
    InlineKeyboardMarkup,
    Message,
    ReplyParameters,
)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("anonbot")

TOKEN = os.environ["BOT_TOKEN"]
PACK = os.getenv("EMOJI_PACK", "solutions_project_by_Solutmark_bot")
BUTTON_EMOJI = os.getenv("BUTTON_EMOJI", "1") != "0"
DB_PATH = os.getenv("DB_PATH", "anon.db")

# ───────────────────────── БД ─────────────────────────
db = sqlite3.connect(DB_PATH)
db.executescript(
    """
    CREATE TABLE IF NOT EXISTS users(
        user_id INTEGER PRIMARY KEY,
        code TEXT UNIQUE NOT NULL,
        mode TEXT NOT NULL DEFAULT 'idle',   -- idle | write | reply
        target INTEGER,                      -- кому пишем
        received INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS msgs(          -- доставленное сообщение -> анонимный отправитель
        recipient_id INTEGER, msg_id INTEGER, sender_id INTEGER,
        PRIMARY KEY(recipient_id, msg_id)
    );
    CREATE TABLE IF NOT EXISTS blocks(
        owner_id INTEGER, sender_id INTEGER, PRIMARY KEY(owner_id, sender_id)
    );
    """
)


def new_code() -> str:
    return secrets.token_urlsafe(6)


def get_user(uid: int) -> sqlite3.Row:
    db.row_factory = sqlite3.Row
    row = db.execute("SELECT * FROM users WHERE user_id=?", (uid,)).fetchone()
    if not row:
        db.execute("INSERT INTO users(user_id, code) VALUES(?,?)", (uid, new_code()))
        db.commit()
        row = db.execute("SELECT * FROM users WHERE user_id=?", (uid,)).fetchone()
    return row


def set_mode(uid: int, mode: str, target: int | None = None) -> None:
    db.execute("UPDATE users SET mode=?, target=? WHERE user_id=?", (mode, target, uid))
    db.commit()


# ───────────────────────── Эмодзи ─────────────────────────
PAW_ID: str | None = None          # custom_emoji_id первой «лапки» из набора
EMOJI_BY_CHAR: dict[str, str] = {}  # обычный эмодзи -> custom_emoji_id из набора
icons_ok = BUTTON_EMOJI


async def load_pack(bot: Bot) -> None:
    global PAW_ID
    try:
        pack = await bot.get_sticker_set(PACK)
    except Exception as e:  # noqa: BLE001
        log.warning("Не удалось загрузить набор эмодзи %s: %s", PACK, e)
        return
    for st in pack.stickers:
        if st.custom_emoji_id:
            EMOJI_BY_CHAR.setdefault(st.emoji or "", st.custom_emoji_id)
    PAW_ID = EMOJI_BY_CHAR.get("🐾") or next(iter(EMOJI_BY_CHAR.values()), None)
    log.info("Загружен набор %s: %d эмодзи", PACK, len(EMOJI_BY_CHAR))


def E(char: str = "🐾") -> str:
    """Кастомный эмодзи в HTML-тексте (если нет в наборе — обычный)."""
    cid = PAW_ID if char == "🐾" else EMOJI_BY_CHAR.get(char)
    return f'<tg-emoji emoji-id="{cid}">{char}</tg-emoji>' if cid else char


def B(text: str, cb: str | None = None, url: str | None = None, icon: bool = True) -> Btn:
    kw = {}
    if icon and icons_ok and PAW_ID:
        kw["icon_custom_emoji_id"] = PAW_ID
    return Btn(text=text, callback_data=cb, url=url, **kw)


# ───────────────────────── Клавиатуры ─────────────────────────
def link_of(bot_username: str, code: str) -> str:
    return f"https://t.me/{bot_username}?start={code}"


def kb_main(link: str) -> InlineKeyboardMarkup:
    share = "https://t.me/share/url?url=" + link + "&text=Напиши%20мне%20анонимно"
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [B("Моя ссылка", "link")],
            [B("Поделиться", url=share)],
            [B("Статистика", "stats"), B("Новая ссылка", "newlink")],
        ]
    )


def kb_back() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[B("В меню", "menu", icon=False)]])


def kb_cancel() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[B("Отмена", "cancel", icon=False)]])


def kb_incoming(msg_id_placeholder: int | None = None) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[[B("Ответить", "reply"), B("Заблокировать", "block", icon=False)]]
    )


def kb_answer(owner_id: int) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[B("Написать ещё", f"w:{owner_id}")]])


def kb_sent(owner_id: int) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[B("Отправить ещё", f"w:{owner_id}")]])


async def with_icons_fallback(fn):
    """Если Telegram не принял иконки на кнопках — отключаем их и повторяем."""
    global icons_ok
    try:
        return await fn()
    except TelegramBadRequest as e:
        if icons_ok and "emoji" in str(e).lower():
            log.warning("Иконки на кнопках недоступны, отключаю: %s", e)
            icons_ok = False
            return await fn()
        raise


# ───────────────────────── Хендлеры ─────────────────────────
router = Router()


async def show_menu(bot: Bot, uid: int, edit: Message | None = None) -> None:
    me = await bot.me()
    u = get_user(uid)
    link = link_of(me.username, u["code"])
    text = (
        f"{E()} <b>Анонимные сообщения</b>\n\n"
        "Вот твоя личная ссылка — поставь её в профиль или в закреп.\n"
        "Любой, кто перейдёт по ней, сможет написать тебе анонимно:\n\n"
        f"<code>{link}</code>"
    )

    async def go():
        kb = kb_main(link)
        if edit:
            await edit.edit_text(text, reply_markup=kb, disable_web_page_preview=True)
        else:
            await bot.send_message(uid, text, reply_markup=kb, disable_web_page_preview=True)

    await with_icons_fallback(go)


@router.message(Command("start"))
async def start(m: Message, command: CommandObject, bot: Bot):
    uid = m.from_user.id
    get_user(uid)
    code = (command.args or "").strip()
    if code:
        owner = db.execute("SELECT user_id FROM users WHERE code=?", (code,)).fetchone()
        if not owner:
            await m.answer("Ссылка недействительна или была заменена.")
            return await show_menu(bot, uid)
        if owner[0] == uid:
            await m.answer("Это твоя собственная ссылка 🙂")
            return await show_menu(bot, uid)
        blocked = db.execute(
            "SELECT 1 FROM blocks WHERE owner_id=? AND sender_id=?", (owner[0], uid)
        ).fetchone()
        if blocked:
            return await m.answer("Не удалось открыть чат.")
        set_mode(uid, "write", owner[0])
        return await with_icons_fallback(
            lambda: m.answer(
                f"{E()} <b>Анонимное сообщение</b>\n\n"
                "Напиши всё, что хочешь — текст, фото, видео, голосовое, стикер.\n"
                "Получатель не узнает, кто ты.",
                reply_markup=kb_cancel(),
            )
        )
    set_mode(uid, "idle")
    await show_menu(bot, uid)


@router.callback_query(F.data == "menu")
async def cb_menu(c: CallbackQuery, bot: Bot):
    set_mode(c.from_user.id, "idle")
    await c.answer()
    await show_menu(bot, c.from_user.id, edit=c.message)


@router.callback_query(F.data == "link")
async def cb_link(c: CallbackQuery, bot: Bot):
    me = await bot.me()
    link = link_of(me.username, get_user(c.from_user.id)["code"])
    await c.answer()
    await c.message.answer(f"{E()} Твоя ссылка:\n<code>{link}</code>", disable_web_page_preview=True)


@router.callback_query(F.data == "stats")
async def cb_stats(c: CallbackQuery):
    u = get_user(c.from_user.id)
    blocked = db.execute("SELECT COUNT(*) FROM blocks WHERE owner_id=?", (c.from_user.id,)).fetchone()[0]
    await c.answer()
    await c.message.edit_text(
        f"{E()} <b>Статистика</b>\n\nПолучено сообщений: <b>{u['received']}</b>\n"
        f"Заблокировано отправителей: <b>{blocked}</b>",
        reply_markup=kb_back(),
    )


@router.callback_query(F.data == "newlink")
async def cb_newlink(c: CallbackQuery, bot: Bot):
    db.execute("UPDATE users SET code=? WHERE user_id=?", (new_code(), c.from_user.id))
    db.commit()
    await c.answer("Ссылка заменена, старая больше не работает", show_alert=True)
    await show_menu(bot, c.from_user.id, edit=c.message)


@router.callback_query(F.data == "cancel")
async def cb_cancel(c: CallbackQuery):
    set_mode(c.from_user.id, "idle")
    await c.answer("Отменено")
    await c.message.edit_text("Отменено.", reply_markup=kb_back())


@router.callback_query(F.data.startswith("w:"))
async def cb_write(c: CallbackQuery):
    owner = int(c.data[2:])
    set_mode(c.from_user.id, "write", owner)
    await c.answer()
    await c.message.answer("Пиши следующее сообщение 👇", reply_markup=kb_cancel())


@router.callback_query(F.data == "reply")
async def cb_reply(c: CallbackQuery):
    row = db.execute(
        "SELECT sender_id FROM msgs WHERE recipient_id=? AND msg_id=?",
        (c.from_user.id, c.message.message_id),
    ).fetchone()
    if not row:
        return await c.answer("Не нашёл отправителя", show_alert=True)
    set_mode(c.from_user.id, "reply", row[0])
    await c.answer()
    await c.message.answer("Напиши ответ — он придёт анонимно 👇", reply_markup=kb_cancel())


@router.callback_query(F.data == "block")
async def cb_block(c: CallbackQuery):
    row = db.execute(
        "SELECT sender_id FROM msgs WHERE recipient_id=? AND msg_id=?",
        (c.from_user.id, c.message.message_id),
    ).fetchone()
    if not row:
        return await c.answer("Не нашёл отправителя", show_alert=True)
    db.execute("INSERT OR IGNORE INTO blocks VALUES(?,?)", (c.from_user.id, row[0]))
    db.commit()
    await c.answer("Отправитель заблокирован", show_alert=True)
    await c.message.edit_reply_markup(reply_markup=None)


@router.message()
async def any_message(m: Message, bot: Bot):
    uid = m.from_user.id
    u = get_user(uid)
    if u["mode"] not in ("write", "reply") or not u["target"]:
        return await show_menu(bot, uid)
    target, mode = u["target"], u["mode"]

    if mode == "write":
        blocked = db.execute(
            "SELECT 1 FROM blocks WHERE owner_id=? AND sender_id=?", (target, uid)
        ).fetchone()
        if blocked:
            set_mode(uid, "idle")
            return await m.answer("Не удалось отправить сообщение.")

    try:
        header = await with_icons_fallback(
            lambda: bot.send_message(
                target,
                f"{E()} <b>{'Новое анонимное сообщение' if mode == 'write' else 'Анонимный ответ'}</b>",
            )
        )
        kb = kb_incoming() if mode == "write" else kb_answer(uid)
        sent = await with_icons_fallback(
            lambda: bot.copy_message(
                target,
                m.chat.id,
                m.message_id,
                reply_parameters=ReplyParameters(message_id=header.message_id),
                reply_markup=kb,
            )
        )
    except TelegramForbiddenError:
        set_mode(uid, "idle")
        return await m.answer("Получатель заблокировал бота — сообщение не доставлено.")
    except TelegramBadRequest as e:
        log.warning("copy failed: %s", e)
        return await m.answer("Не получилось отправить это сообщение. Попробуй другое.")

    if mode == "write":
        db.execute("INSERT OR REPLACE INTO msgs VALUES(?,?,?)", (target, sent.message_id, uid))
        db.execute("UPDATE users SET received=received+1 WHERE user_id=?", (target,))
        db.commit()
    # В режиме reply отправитель-ответчик (владелец) остаётся анонимным для автора:
    # автор может ответить обратно через кнопку "Написать ещё" (write -> владельцу).
    set_mode(uid, "idle")
    await with_icons_fallback(
        lambda: m.answer(f"{E()} Отправлено анонимно!", reply_markup=kb_sent(target) if mode == "write" else kb_back())
    )


async def main():
    bot = Bot(TOKEN, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    await load_pack(bot)
    dp = Dispatcher()
    dp.include_router(router)
    await bot.delete_webhook()
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
