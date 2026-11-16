"""ID-бот: свой ID, ID по @username / контакту / ссылке / пересланному, переход по ID, дата регистрации."""
from __future__ import annotations

import asyncio
import logging
import os
import re
from html import escape
from typing import Callable

from aiogram import Bot, Dispatcher, F, Router
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramBadRequest
from aiogram.filters import Command, CommandStart
from aiogram.types import (
    CallbackQuery, CopyTextButton, InlineKeyboardButton, InlineKeyboardMarkup,
    KeyboardButton, KeyboardButtonRequestChat, KeyboardButtonRequestUsers, Message,
    ReplyKeyboardMarkup, User,
)

import emojis
import regdate
from emojis import e

log = logging.getLogger("idbot")
router = Router()
ADMINS = {int(x) for x in os.getenv("ADMINS", "").replace(" ", "").split(",") if x}
MODES: dict[int, str] = {}          # user_id -> ожидаемый ввод
FANCY = {"buttons": True}           # отключается, если Telegram не принял иконки на кнопках

# ───────────────────────── кнопки / отправка ─────────────────────────

Factory = Callable[[bool], tuple[str, InlineKeyboardMarkup | None]]


def btn(label: str, key: str, f: bool, **kw) -> InlineKeyboardButton:
    """Кнопка с кастомным эмодзи (если доступно) или обычным в тексте."""
    icon = emojis.cid(key) if (f and FANCY["buttons"]) else None
    if icon:
        return InlineKeyboardButton(text=label, icon_custom_emoji_id=icon, **kw)
    return InlineKeyboardButton(text=f"{emojis.SLOTS[key][0]} {label}", **kw)


def kb(*rows: list[InlineKeyboardButton]) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=list(rows))


async def show(target: Message | CallbackQuery, make: Factory, edit: bool = False, **extra) -> None:
    """Отправить/отредактировать; при отказе Telegram от кастомных эмодзи — повтор без них."""
    for fancy in (True, False):
        text, markup = make(fancy)
        try:
            if isinstance(target, CallbackQuery):
                if edit and target.message:
                    await target.message.edit_text(text, reply_markup=markup,
                                                   disable_web_page_preview=True)
                elif target.message:
                    await target.message.answer(text, reply_markup=markup,
                                                disable_web_page_preview=True)
            else:
                await target.answer(text, reply_markup=extra.get("reply_markup", markup),
                                    disable_web_page_preview=True)
            return
        except TelegramBadRequest as ex:
            if "not modified" in str(ex):
                return
            if not fancy:
                raise
            log.warning("Кастомные эмодзи отклонены (%s) — отправляю обычные", ex)
            FANCY["buttons"] = False


# ───────────────────────── меню ─────────────────────────

def menu(f: bool) -> tuple[str, InlineKeyboardMarkup]:
    text = (f"{e('id', f)} <b>ID-бот</b>\n\n"
            "Узнаю ID пользователей, групп и каналов, дату регистрации аккаунта "
            "и помогаю открыть профиль или чат по ID.\n\n"
            "Выбери действие или просто пришли @username, ссылку, ID, контакт "
            "или пересланное сообщение.")
    return text, kb(
        [btn("Мой ID", "me", f, callback_data="m:me"),
         btn("По username", "search", f, callback_data="m:user")],
        [btn("По контакту", "contact", f, callback_data="m:contact"),
         btn("По ссылке", "link", f, callback_data="m:link")],
        [btn("Открыть по ID", "id", f, callback_data="m:id"),
         btn("Дата регистрации", "date", f, callback_data="m:date")],
    )


def back_row(f: bool) -> list[InlineKeyboardButton]:
    return [btn("В меню", "back", f, callback_data="menu")]


PROMPTS = {
    "user": ("search", "Пришли <b>@username</b> пользователя, группы или канала."),
    "contact": ("contact", "Пришли <b>контакт</b> (скрепка → Контакт) или нажми кнопку ниже, "
                           "чтобы выбрать пользователя/чат из списка — так ID выдаётся "
                           "даже для тех, у кого нет username."),
    "link": ("link", "Пришли <b>ссылку</b>: <code>t.me/name</code>, <code>t.me/c/…</code> "
                     "или пригласительную <code>t.me/+…</code> (для закрытых чатов — "
                     "перешли сюда любое сообщение из чата или выбери чат кнопкой)."),
    "id": ("id", "Пришли <b>числовой ID</b> — дам кнопки «Открыть профиль» / «Открыть чат»."),
    "date": ("date", "Пришли <b>@username</b>, <b>ID</b> или контакт — оценю дату регистрации."),
}

MAIN_KB = ReplyKeyboardMarkup(
    keyboard=[
        [KeyboardButton(text="🪪 Мой ID"), KeyboardButton(text="🔍 По username")],
        [KeyboardButton(text="📇 По контакту"), KeyboardButton(text="🔗 По ссылке")],
        [KeyboardButton(text="🔢 Открыть по ID"), KeyboardButton(text="📅 Дата регистрации")],
        [KeyboardButton(text="👤 Выбрать пользователя",
                        request_users=KeyboardButtonRequestUsers(request_id=1, request_username=True)),
         KeyboardButton(text="👥 Выбрать группу",
                        request_chat=KeyboardButtonRequestChat(request_id=2, chat_is_channel=False,
                                                               request_title=True, request_username=True))],
        [KeyboardButton(text="📢 Выбрать канал",
                        request_chat=KeyboardButtonRequestChat(request_id=3, chat_is_channel=True,
                                                               request_title=True, request_username=True)),
         KeyboardButton(text="❓ Помощь")],
    ],
    resize_keyboard=True, is_persistent=True, input_field_placeholder="@username, ссылка или ID",
)

# команды из меню «/» (set_my_commands) и русские слова без слеша
COMMANDS = [
    ("me", "🪪 Мой ID"), ("user", "🔍 Узнать ID по username"), ("contact", "📇 ID по контакту"),
    ("link", "🔗 ID группы по ссылке"), ("id", "🔢 Открыть профиль/чат по ID"),
    ("date", "📅 Дата регистрации аккаунта"), ("menu", "🏠 Главное меню"),
    ("help", "❓ Помощь по боту"),
]
ALIASES = {
    "me": ["мой id", "мой айди", "мой ид", "моё id", "айди", "ид"],
    "user": ["по username", "по юзернейму", "юзернейм", "username", "юзер"],
    "contact": ["по контакту", "контакт"],
    "link": ["по ссылке", "ссылка"],
    "id": ["открыть по id", "открыть по айди", "по id", "по айди"],
    "date": ["дата регистрации", "дата", "регистрация"],
    "menu": ["меню", "старт", "начать", "главное меню"],
    "help": ["помощь", "справка", "help", "команды"],
    "cancel": ["отмена", "отменить", "стоп"],
}
WORD2ACTION = {w: a for a, ws in ALIASES.items() for w in ws}


def norm(t: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", "", t.lower())).strip()


HELP = ("<b>Команды</b> (со слешем или русскими словами без него):\n"
        "• <b>мой id</b> — /me\n• <b>по username</b> — /user\n• <b>по контакту</b> — /contact\n"
        "• <b>по ссылке</b> — /link\n• <b>открыть по id</b> — /id\n"
        "• <b>дата регистрации</b> — /date\n• <b>меню</b> — /menu\n• <b>отмена</b>\n\n"
        "Можно просто прислать @username, ссылку, число-ID, контакт или пересланное сообщение. "
        "Кнопки внизу работают так же.")


async def go_menu(m: Message) -> None:
    MODES.pop(m.from_user.id, None)
    await m.answer("Клавиатура внизу включена ⌨️", reply_markup=MAIN_KB)
    await show(m, lambda f: menu(f))


async def enter_mode(m: Message, uid: int, mode: str) -> None:
    """Выполнить действие меню (из слеш-команды, слов, кнопки внизу)."""
    if mode == "me":
        await show(m, lambda f: me_card(m.from_user, f))
    elif mode == "menu":
        await go_menu(m)
    elif mode == "help":
        await show(m, lambda f: (HELP, kb(back_row(f))))
    elif mode == "cancel":
        MODES.pop(uid, None)
        await m.answer("Отменено.", reply_markup=MAIN_KB)
    else:
        MODES[uid] = mode
        key, text = PROMPTS[mode]
        await show(m, lambda f: (f"{e(key, f)} {text}", kb(back_row(f))))


@router.message(CommandStart())
@router.message(Command("menu"))
async def cmd_start(m: Message) -> None:
    await go_menu(m)


@router.message(Command(*[c for c, _ in COMMANDS if c != "menu"]))
async def cmd_actions(m: Message) -> None:
    cmd = (m.text or "").split()[0][1:].split("@")[0].lower()
    await enter_mode(m, m.from_user.id, cmd)


@router.message(F.text.func(lambda t: norm(t) in WORD2ACTION))
async def on_alias(m: Message) -> None:
    await enter_mode(m, m.from_user.id, WORD2ACTION[norm(m.text)])


@router.callback_query(F.data == "menu")
async def cb_menu(c: CallbackQuery) -> None:
    MODES.pop(c.from_user.id, None)
    await c.answer()
    await show(c, lambda f: menu(f), edit=True)


@router.callback_query(F.data.startswith("m:"))
async def cb_mode(c: CallbackQuery) -> None:
    mode = c.data[2:]
    await c.answer()
    if mode == "me":
        await show(c, lambda f: me_card(c.from_user, f), edit=True)
        return
    MODES[c.from_user.id] = mode
    key, text = PROMPTS[mode]
    await show(c, lambda f: (f"{e(key, f)} {text}", kb(back_row(f))), edit=True)


# ───────────────────────── карточки ─────────────────────────

def fullname(first: str | None, last: str | None) -> str:
    return escape(" ".join(x for x in (first, last) if x) or "—")


def open_url(uid: int, username: str | None = None) -> str:
    if username:
        return f"https://t.me/{username}"
    if uid > 0:
        return f"tg://user?id={uid}"
    s = str(uid)
    return f"https://t.me/c/{s[4:]}/1" if s.startswith("-100") else ""


def reg_line(uid: int, f: bool) -> str:
    est = regdate.estimate(uid)
    return f"{e('date', f)} Регистрация: <b>≈ {est.human()}</b> <i>(оценка)</i>"


def card(uid: int, kind: str, title: str, username: str | None, extra: list[str],
         f: bool, show_reg: bool = True) -> tuple[str, InlineKeyboardMarkup]:
    key = {"user": "user", "group": "group", "channel": "channel"}[kind]
    lines = [f"{e(key, f)} <b>{title}</b>", f"{e('id', f)} ID: <code>{uid}</code>"]
    if username:
        lines.append(f"{e('link', f)} Username: @{escape(username)}")
    lines += extra
    if kind == "user" and show_reg:
        lines.append(reg_line(uid, f))
    rows: list[list[InlineKeyboardButton]] = []
    url = open_url(uid, username)
    first = [btn("Копировать ID", "id", f, copy_text=CopyTextButton(text=str(uid)))]
    rows.append(first)
    if url:
        label = "Открыть профиль" if kind == "user" else "Открыть чат"
        rows.append([btn(label, key, f, url=url)])
    if kind == "user":
        rows.append([btn("Как оценена дата", "date", f, callback_data=f"reg:{uid}")])
    rows.append(back_row(f))
    return "\n".join(lines), kb(*rows)


def me_card(u: User, f: bool) -> tuple[str, InlineKeyboardMarkup]:
    extra = []
    if u.language_code:
        extra.append(f"{e('link', f)} Язык: <code>{u.language_code}</code>")
    if u.is_premium:
        extra.append(f"{e('star', f)} Telegram Premium")
    return card(u.id, "user", fullname(u.first_name, u.last_name), u.username, extra, f)


def minimal_user(uid: int, f: bool, name: str = "Пользователь",
                 username: str | None = None) -> tuple[str, InlineKeyboardMarkup]:
    return card(uid, "user", escape(name), username, [], f)


# ───────────────────────── разбор ввода ─────────────────────────

USERNAME = r"[A-Za-z][A-Za-z0-9_]{3,31}"
RE_INVITE = re.compile(r"(?:https?://)?(?:t|telegram)\.me/(?:\+|joinchat/)([\w-]+)", re.I)
RE_PRIVATE = re.compile(r"(?:https?://)?(?:t|telegram)\.me/c/(\d+)", re.I)
RE_PUBLIC = re.compile(rf"(?:https?://)?(?:t|telegram)\.me/({USERNAME})(?:[/?#].*)?$", re.I)
RE_TG = re.compile(rf"tg://resolve\?domain=({USERNAME})", re.I)
RE_AT = re.compile(rf"^@?({USERNAME})$")
RE_ID = re.compile(r"^(-?\d{5,15})$")


async def resolve_and_show(m: Message, text: str, want_date: bool) -> bool:
    text = text.strip()
    f = True
    if RE_INVITE.search(text):
        await show(m, lambda f: (
            f"{e('warn', f)} <b>Пригласительная ссылка</b> — закрытый чат: Bot API не отдаёт "
            "его ID без вступления.\n\nПерешли сюда любое сообщение из этого чата или "
            "нажми «Выбрать группу» — и получишь ID.", kb(back_row(f))))
        return True

    uid: int | None = None
    target: int | str | None = None
    if (mm := RE_ID.match(text)):
        uid = int(mm.group(1))
        target = uid
    elif (mm := RE_PRIVATE.search(text)):
        uid = int("-100" + mm.group(1))
        target = uid
    else:
        mm = RE_TG.search(text) or RE_PUBLIC.match(text) or RE_AT.match(text)
        if mm:
            target = "@" + mm.group(1)
    if target is None:
        return False

    try:
        ch = await m.bot.get_chat(target)
    except TelegramBadRequest:
        if uid is not None and uid > 0:  # профиль по ID открыть можно и без данных
            await show(m, lambda f: minimal_user(uid, f))
            return True
        await show(m, lambda f: (
            f"{e('warn', f)} Не нашёл <code>{escape(str(target))}</code>.\n"
            "Бот видит только публичные username и чаты, где он состоит. "
            "Для остального — кнопки выбора или пересылка сообщения.", kb(back_row(f))))
        return True

    await show(m, lambda f: chat_card(ch, f))
    return True


def chat_card(ch, f: bool) -> tuple[str, InlineKeyboardMarkup]:
    extra = []
    if getattr(ch, "bio", None):
        extra.append(f"{e('contact', f)} Био: {escape(ch.bio)}")
    if getattr(ch, "description", None):
        extra.append(f"{e('contact', f)} {escape(ch.description[:200])}")
    if ch.type == "private":
        return card(ch.id, "user", fullname(ch.first_name, ch.last_name), ch.username, extra, f)
    kind = "channel" if ch.type == "channel" else "group"
    if ch.type == "supergroup" and getattr(ch, "is_forum", False):
        extra.append(f"{e('group', f)} Форум с темами")
    return card(ch.id, kind, escape(ch.title or "—"), ch.username, extra, f)


# ───────────────────────── дата регистрации ─────────────────────────

@router.callback_query(F.data.startswith("reg:"))
async def cb_reg(c: CallbackQuery) -> None:
    uid = int(c.data[4:])
    await c.answer()
    est = regdate.estimate(uid)
    d = est.date
    how = ("между двумя ближайшими опорными ID" if est.approx_kind == "interpolation"
           else "экстраполяция за пределами известных опорных ID — менее точно")

    def make(f: bool):
        text = (f"{e('date', f)} <b>Дата регистрации</b>\n"
                f"{e('id', f)} ID: <code>{uid}</code>\n\n"
                f"≈ <b>{d:%d.%m.%Y}</b> (±{est.err_days} дн.)\n\n"
                f"<i>Telegram не раскрывает дату регистрации. Оценка строится по ID: "
                f"{how}. ID выдаются по возрастанию, поэтому точность — месяцы, "
                f"а не дни.</i>")
        return text, kb([btn("Назад к карточке", "back", f, callback_data=f"card:{uid}")], back_row(f))

    await show(c, make, edit=True)


@router.callback_query(F.data.startswith("card:"))
async def cb_card(c: CallbackQuery) -> None:
    uid = int(c.data[5:])
    await c.answer()
    try:
        ch = await c.bot.get_chat(uid)
        await show(c, lambda f: chat_card(ch, f), edit=True)
    except TelegramBadRequest:
        await show(c, lambda f: minimal_user(uid, f), edit=True)


@router.message(Command("anchor"))
async def cmd_anchor(m: Message) -> None:
    """/anchor <id> <ГГГГ-ММ-ДД> — добавить опорную точку (только ADMINS)."""
    if m.from_user.id not in ADMINS:
        return
    parts = (m.text or "").split()
    try:
        from datetime import datetime, timezone
        uid = int(parts[1])
        ts = int(datetime.strptime(parts[2], "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp())
    except (IndexError, ValueError):
        await m.answer("Формат: /anchor 123456789 2021-05-17")
        return
    regdate.add_anchor(uid, ts)
    await m.answer("Опорная точка сохранена ✅")


@router.message(Command("emojis"))
async def cmd_emojis(m: Message) -> None:
    if m.from_user.id not in ADMINS:
        return
    d = emojis.dump()
    await m.answer("Эмодзи пака: " + (", ".join(f"{k}→{v}" for k, v in list(d.items())[:60]) or "пусто"))


# ───────────────────────── входящие данные ─────────────────────────

@router.message(F.contact)
async def on_contact(m: Message) -> None:
    c = m.contact
    if not c.user_id:
        await show(m, lambda f: (f"{e('warn', f)} У контакта нет аккаунта Telegram "
                                 "или ID скрыт.", kb(back_row(f))))
        return
    name = " ".join(x for x in (c.first_name, c.last_name) if x)
    try:
        ch = await m.bot.get_chat(c.user_id)
        await show(m, lambda f: chat_card(ch, f))
    except TelegramBadRequest:
        await show(m, lambda f: minimal_user(c.user_id, f, name))


@router.message(F.users_shared)
async def on_users_shared(m: Message) -> None:
    for u in m.users_shared.users:
        name = " ".join(x for x in (u.first_name, u.last_name) if x) or "Пользователь"
        await show(m, lambda f, u=u, name=name: minimal_user(u.user_id, f, name, u.username))


@router.message(F.chat_shared)
async def on_chat_shared(m: Message) -> None:
    cs = m.chat_shared
    kind = "channel" if cs.request_id == 3 else "group"
    await show(m, lambda f: card(cs.chat_id, kind, escape(cs.title or "Чат"), cs.username, [], f))


@router.message(F.forward_origin)
async def on_forward(m: Message) -> None:
    o = m.forward_origin
    if o.type == "user":
        u = o.sender_user
        await show(m, lambda f: me_card(u, f))
    elif o.type in ("chat", "channel"):
        ch = o.sender_chat if o.type == "chat" else o.chat
        kind = "channel" if ch.type == "channel" else "group"
        await show(m, lambda f: card(ch.id, kind, escape(ch.title or "Чат"), ch.username, [], f))
    else:
        await show(m, lambda f: (f"{e('warn', f)} Автор скрыл аккаунт в настройках "
                                 f"приватности — ID недоступен ({escape(o.sender_user_name)}).",
                                 kb(back_row(f))))


@router.message(F.text & ~F.text.startswith("/"))
async def on_text(m: Message) -> None:
    mode = MODES.get(m.from_user.id)
    if await resolve_and_show(m, m.text, mode == "date"):
        return
    await show(m, lambda f: (f"{e('warn', f)} Не понял. Пришли @username, ссылку, ID, "
                             "контакт или пересланное сообщение.", menu(f)[1]))


async def main() -> None:
    logging.basicConfig(level=logging.INFO)
    bot = Bot(os.environ["BOT_TOKEN"], default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    await emojis.load(bot, os.getenv("EMOJI_PACK", "solutions_project_by_Solutmark_bot"))
    from aiogram.types import BotCommand
    await bot.set_my_commands([BotCommand(command=c, description=d) for c, d in COMMANDS])
    dp = Dispatcher()
    dp.include_router(router)
    await bot.delete_webhook(drop_pending_updates=True)
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
