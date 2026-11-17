"""Бот анонимных сообщений.

/start -> личная ссылка вида t.me/<bot>?start=<код>. Её ставят в профиль.
Любой, кто перешёл по ссылке, пишет сообщение — владельцу оно приходит анонимно.
Владелец может ответить (тоже анонимно) или заблокировать отправителя.
"""
import asyncio
import logging
import os
import re
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
    BotCommand,
    InlineKeyboardMarkup,
    KeyboardButton as KBtn,
    Message,
    ReplyKeyboardMarkup,
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
    PAW_ID = (
        os.getenv("PAW_EMOJI_ID")
        or EMOJI_BY_CHAR.get("🐾")
        or next(iter(EMOJI_BY_CHAR.values()), None)
    )
    log.info("Набор %s: %d эмодзи, лапка id=%s", PACK, len(EMOJI_BY_CHAR), PAW_ID)
    if not PAW_ID:
        log.warning("Кастомные эмодзи НЕ загружены — будут обычные. Проверь EMOJI_PACK и токен.")


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



def KB(text: str) -> KBtn:
    kw = {}
    if icons_ok and PAW_ID:
        kw["icon_custom_emoji_id"] = PAW_ID
    return KBtn(text=text, **kw)


def rk_main() -> ReplyKeyboardMarkup:
    """Постоянная клавиатура внизу экрана."""
    return ReplyKeyboardMarkup(
        keyboard=[
            [KB("Моя ссылка"), KB("Поделиться")],
            [KB("Статистика"), KB("Новая ссылка")],
            [KB("Помощь")],
        ],
        resize_keyboard=True,
        is_persistent=True,
        input_field_placeholder="Выбери действие или напиши команду",
    )


def rk_cancel() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[[KB("Отмена")]],
        resize_keyboard=True,
        input_field_placeholder="Напиши анонимное сообщение…",
    )


# Команды без слэша: нормализованный текст -> действие
TEXT_COMMANDS = {
    "моя ссылка": "link", "ссылка": "link", "мою ссылку": "link", "линк": "link",
    "поделиться": "share", "расшарить": "share",
    "статистика": "stats", "стата": "stats",
    "новая ссылка": "newlink", "сменить ссылку": "newlink", "заменить ссылку": "newlink",
    "меню": "menu", "старт": "menu", "начать": "menu",
    "помощь": "help", "справка": "help", "помоги": "help",
}
CANCEL_WORDS = {"отмена", "отменить", "назад", "стоп"}


def norm(text: str) -> str:
    """Убираем эмодзи/знаки/регистр: '🔗 Моя ссылка!' -> 'моя ссылка'."""
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", "", text.lower())).strip()


BOT_COMMANDS = [
    BotCommand(command="start", description="🏠 Меню и моя ссылка"),
    BotCommand(command="link", description="🔗 Моя ссылка для анонимок"),
    BotCommand(command="share", description="📤 Поделиться ссылкой"),
    BotCommand(command="stats", description="📊 Статистика"),
    BotCommand(command="newlink", description="🔄 Сменить ссылку"),
    BotCommand(command="help", description="🆘 Помощь"),
    BotCommand(command="cancel", description="❌ Отменить отправку"),
]


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

HELP = (
    f"{E()} <b>Как это работает</b>\n\n"
    "1. Поставь свою ссылку в профиль или закреп.\n"
    "2. Кто перейдёт по ней — напишет тебе анонимно.\n"
    "3. Ты можешь ответить (тоже анонимно) или заблокировать отправителя.\n\n"
    "<b>Команды</b> — можно кнопками внизу, из меню «/» или обычным текстом, без слэша:\n"
    "• <b>моя ссылка</b> — показать ссылку\n"
    "• <b>поделиться</b> — отправить ссылку друзьям\n"
    "• <b>статистика</b> — сколько сообщений получено\n"
    "• <b>новая ссылка</b> — заменить ссылку (старая перестанет работать)\n"
    "• <b>меню</b>, <b>помощь</b>, <b>отмена</b>"
)


async def user_link(bot: Bot, uid: int) -> str:
    me = await bot.me()
    return link_of(me.username, get_user(uid)["code"])


async def send(bot: Bot, uid: int, text: str, kb=None, edit: Message | None = None):
    async def go():
        k = kb() if callable(kb) else kb
        if edit:
            await edit.edit_text(text, reply_markup=k, disable_web_page_preview=True)
        else:
            await bot.send_message(uid, text, reply_markup=k, disable_web_page_preview=True)

    await with_icons_fallback(go)


async def show_menu(bot: Bot, uid: int, edit: Message | None = None, welcome: bool = False) -> None:
    link = await user_link(bot, uid)
    if welcome:  # цепляем постоянную клавиатуру внизу
        await send(bot, uid, f"{E()} Привет! Кнопки управления — внизу 👇", rk_main)
    text = (
        f"{E()} <b>Анонимные сообщения</b>\n\n"
        "Вот твоя личная ссылка — поставь её в профиль или в закреп.\n"
        "Любой, кто перейдёт по ней, сможет написать тебе анонимно:\n\n"
        f"<code>{link}</code>"
    )
    await send(bot, uid, text, lambda: kb_main(link), edit)


async def do_link(bot: Bot, uid: int):
    link = await user_link(bot, uid)
    await send(bot, uid, f"{E()} Твоя ссылка:\n<code>{link}</code>", rk_main)


async def do_share(bot: Bot, uid: int):
    link = await user_link(bot, uid)
    url = "https://t.me/share/url?url=" + link + "&text=Напиши%20мне%20анонимно"
    await send(
        bot, uid, f"{E()} Нажми, чтобы отправить ссылку друзьям:",
        lambda: InlineKeyboardMarkup(inline_keyboard=[[B("Поделиться ссылкой", url=url)]]),
    )


async def do_stats(bot: Bot, uid: int, edit: Message | None = None):
    u = get_user(uid)
    blocked = db.execute("SELECT COUNT(*) FROM blocks WHERE owner_id=?", (uid,)).fetchone()[0]
    text = (
        f"{E()} <b>Статистика</b>\n\nПолучено сообщений: <b>{u['received']}</b>\n"
        f"Заблокировано отправителей: <b>{blocked}</b>"
    )
    await send(bot, uid, text, kb_back(), edit)


async def do_newlink(bot: Bot, uid: int):
    db.execute("UPDATE users SET code=? WHERE user_id=?", (new_code(), uid))
    db.commit()
    link = await user_link(bot, uid)
    await send(
        bot, uid,
        f"{E()} Ссылка заменена, старая больше не работает.\n\n<code>{link}</code>",
        rk_main,
    )


async def do_cancel(bot: Bot, uid: int):
    set_mode(uid, "idle")
    await send(bot, uid, "Отменено.", rk_main)


async def do_help(bot: Bot, uid: int):
    await send(bot, uid, HELP, rk_main)


ACTIONS = {
    "link": do_link, "share": do_share, "stats": do_stats,
    "newlink": do_newlink, "help": do_help, "menu": None,
}


async def run_action(bot: Bot, uid: int, action: str):
    set_mode(uid, "idle")
    if action == "menu":
        await show_menu(bot, uid)
    else:
        await ACTIONS[action](bot, uid)


@router.message(Command("start"))
async def start(m: Message, command: CommandObject, bot: Bot):
    uid = m.from_user.id
    get_user(uid)
    code = (command.args or "").strip()
    if code:
        owner = db.execute("SELECT user_id FROM users WHERE code=?", (code,)).fetchone()
        if not owner:
            await m.answer("Ссылка недействительна или была заменена.")
            return await show_menu(bot, uid, welcome=True)
        if owner[0] == uid:
            await m.answer("Это твоя собственная ссылка 🙂")
            return await show_menu(bot, uid, welcome=True)
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
                reply_markup=rk_cancel(),
            )
        )
    set_mode(uid, "idle")
    await show_menu(bot, uid, welcome=True)


@router.message(Command("link", "share", "stats", "newlink", "help", "menu"))
async def slash_cmds(m: Message, command: CommandObject, bot: Bot):
    await run_action(bot, m.from_user.id, command.command)


@router.message(Command("emoji"))
async def emoji_diag(m: Message, bot: Bot):
    """Диагностика кастомных эмодзи."""
    if not PAW_ID:
        return await m.answer(
            f"Набор <code>{PACK}</code> не загрузился (см. логи бота) — эмодзи не из чего брать."
        )
    sample = " ".join(f'<tg-emoji emoji-id="{i}">{ch or "▫️"}</tg-emoji>' for ch, i in list(EMOJI_BY_CHAR.items())[:10])
    await m.answer(
        f"Набор: <code>{PACK}</code>, эмодзи: {len(EMOJI_BY_CHAR)}\n"
        f"Лапка: <code>{PAW_ID}</code>\nИконки на кнопках: {'вкл' if icons_ok else 'выкл (Telegram отклонил)'}\n\n"
        f"Тест: {sample}\n\n"
        "Если выше обычные эмодзи, а не из набора — Telegram разрешает боту кастомные эмодзи "
        "только если у владельца бота (аккаунта, создавшего его в @BotFather) есть Telegram Premium "
        "или у бота куплен доп. username на Fragment."
    )


@router.message(Command("cancel"))
async def slash_cancel(m: Message, bot: Bot):
    await do_cancel(bot, m.from_user.id)


@router.callback_query(F.data == "menu")
async def cb_menu(c: CallbackQuery, bot: Bot):
    set_mode(c.from_user.id, "idle")
    await c.answer()
    await show_menu(bot, c.from_user.id, edit=c.message)


@router.callback_query(F.data == "link")
async def cb_link(c: CallbackQuery, bot: Bot):
    await c.answer()
    await do_link(bot, c.from_user.id)


@router.callback_query(F.data == "stats")
async def cb_stats(c: CallbackQuery, bot: Bot):
    await c.answer()
    await do_stats(bot, c.from_user.id, edit=c.message)


@router.callback_query(F.data == "newlink")
async def cb_newlink(c: CallbackQuery, bot: Bot):
    await c.answer()
    await do_newlink(bot, c.from_user.id)


@router.callback_query(F.data == "cancel")
async def cb_cancel(c: CallbackQuery, bot: Bot):
    await c.answer("Отменено")
    await do_cancel(bot, c.from_user.id)


@router.callback_query(F.data.startswith("w:"))
async def cb_write(c: CallbackQuery):
    owner = int(c.data[2:])
    set_mode(c.from_user.id, "write", owner)
    await c.answer()
    await c.message.answer("Пиши следующее сообщение 👇", reply_markup=rk_cancel())


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
    await c.message.answer("Напиши ответ — он придёт анонимно 👇", reply_markup=rk_cancel())


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
    in_chat = u["mode"] in ("write", "reply") and u["target"]
    word = norm(m.text) if m.text else ""

    # Русские команды без слэша. В режиме написания перехватываем только «Отмена»,
    # чтобы обычные слова в анонимке не принимались за команды.
    if in_chat:
        if word in CANCEL_WORDS:
            return await do_cancel(bot, uid)
    else:
        if word in CANCEL_WORDS:
            return await do_cancel(bot, uid)
        if word in TEXT_COMMANDS:
            return await run_action(bot, uid, TEXT_COMMANDS[word])
        return await show_menu(bot, uid, welcome=True)

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
    # возвращаем постоянную клавиатуру внизу (вместо «Отмена»)
    await send(bot, uid, f"{E()} Отправлено анонимно! Чтобы написать ещё — открой ссылку снова.", rk_main)


async def main():
    bot = Bot(TOKEN, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    await load_pack(bot)
    await bot.set_my_commands(BOT_COMMANDS)
    dp = Dispatcher()
    dp.include_router(router)
    await bot.delete_webhook()
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
