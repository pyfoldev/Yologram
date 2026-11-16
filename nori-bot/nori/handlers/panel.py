"""Меню настроек в личке с ботом."""
from __future__ import annotations

from aiogram import Bot, F, Router
from aiogram.exceptions import TelegramAPIError
from aiogram.filters import Command, CommandObject
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

from nori.db import Database
from nori.handlers import games
from nori.handlers.commands import HELP
from nori.utils import esc, fmt_duration, parse_hhmm

router = Router(name="panel")
AWAIT: dict[int, dict] = {}
COOLDOWNS = (30, 60, 180, 360, 720, 1440)


def kb(rows) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[InlineKeyboardButton(text=t, callback_data=d) for t, d in r] for r in rows])


BACK = [("◀️ Назад", "p:home")]


def mark(on: bool) -> str:
    return "✅" if on else "❌"


async def home(db: Database, uid: int) -> tuple[str, InlineKeyboardMarkup]:
    conns = [c for c in await db.connections_of(uid) if c["enabled"]]
    status = "🟢 подключён" if conns else "🔴 не подключён"
    text = (f"<b>Nori — бизнес-бот</b>\nСтатус: {status}\n\n"
            + ("" if conns else "Подключите: Настройки Telegram → Business → Чат-боты → укажите этого бота.\n\n")
            + "Я присылаю сюда удалённые и изменённые сообщения, исчезающее медиа, отвечаю за вас и умею ещё много всего.")
    f = lambda k: db.flag(uid, k)
    rows = [
        [(f"{mark(await f('notify_deleted'))} Удалённые", "p:t:notify_deleted"), (f"{mark(await f('notify_edited'))} Правки", "p:t:notify_edited")],
        [(f"{mark(await f('save_media'))} Исчезающее медиа", "p:t:save_media"), (f"{mark(await f('del_cmd'))} Чистить команды", "p:t:del_cmd")],
        [(f"🤖 Автоответчик {mark(await f('ar_on'))}", "p:ar"), ("🔇 Муты", "p:mutes")],
        [("🔑 Триггеры", "p:trg"), ("📝 Шаблоны", "p:tpl")],
        [("⏰ Напоминания", "p:rem"), ("📊 Статистика", "p:stats")],
        [("🎮 Мини-игры", "g:menu"), ("❓ Команды", "p:help")],
    ]
    return text, kb(rows)


async def _edit(cb: CallbackQuery, text: str, markup: InlineKeyboardMarkup) -> None:
    try:
        await cb.message.edit_text(text, reply_markup=markup)
    except TelegramAPIError:
        pass


@router.message(Command("start", "menu"), F.chat.type == "private")
async def start(m: Message, command: CommandObject, db: Database) -> None:
    AWAIT.pop(m.from_user.id, None)
    if command.args == "games":
        return await games.cmd_games(m, db)
    text, markup = await home(db, m.from_user.id)
    await m.answer(text, reply_markup=markup)


@router.message(Command("help"), F.chat.type == "private")
async def help_(m: Message) -> None:
    await m.answer(HELP)


async def ar_screen(db: Database, uid: int) -> tuple[str, InlineKeyboardMarkup]:
    on, mode = await db.flag(uid, "ar_on"), await db.get(uid, "ar_mode")
    sched = f"{await db.get(uid, 'ar_from')}–{await db.get(uid, 'ar_to')} (UTC+{await db.get(uid, 'tz')})"
    text = (f"🤖 <b>Автоответчик</b>: {'включён' if on else 'выключен'}\n"
            f"Когда: {'всегда' if mode == 'always' else 'по расписанию ' + sched}\n"
            f"Не чаще раза в {fmt_duration(int(await db.get(uid, 'ar_cooldown')) * 60)} одному человеку; "
            "молчит, если вы сами только что писали в чат.\n\n"
            f"<b>Текст</b> ({{name}} — имя собеседника):\n<blockquote>{esc(await db.get(uid, 'ar_text'))}</blockquote>")
    return text, kb([
        [(f"{mark(on)} Включён", "p:t:ar_on"), ("🕒 " + ("Всегда" if mode == "always" else "По расписанию"), "p:ar:mode")],
        [("✏️ Текст", "p:ar:text"), ("⏱ Расписание", "p:ar:sched")],
        [("🔁 Интервал", "p:ar:cd"), ("🌍 Часовой пояс", "p:ar:tz")],
        BACK,
    ])


@router.callback_query(F.data.startswith("p:"))
async def on_panel(cb: CallbackQuery, bot: Bot, db: Database) -> None:
    uid = cb.from_user.id
    parts = cb.data.split(":")
    a = parts[1]
    AWAIT.pop(uid, None)
    if a == "home":
        text, markup = await home(db, uid)
        await _edit(cb, text, markup)
    elif a == "t":
        await db.toggle(uid, parts[2])
        if parts[2] == "ar_on":
            text, markup = await ar_screen(db, uid)
        else:
            text, markup = await home(db, uid)
        await _edit(cb, text, markup)
    elif a == "ar":
        sub = parts[2] if len(parts) > 2 else None
        if sub == "mode":
            await db.set(uid, "ar_mode", "schedule" if await db.get(uid, "ar_mode") == "always" else "always")
        elif sub == "cd":
            cur = int(await db.get(uid, "ar_cooldown"))
            await db.set(uid, "ar_cooldown", str(COOLDOWNS[(COOLDOWNS.index(cur) + 1) % len(COOLDOWNS)] if cur in COOLDOWNS else COOLDOWNS[0]))
        elif sub in ("text", "sched", "tz"):
            AWAIT[uid] = {"what": f"ar_{sub}"}
            prompt = {"text": "Пришлите новый текст автоответа. Можно использовать {name}.",
                      "sched": "Пришлите время, когда отвечать, в формате <code>22:00-09:00</code>.",
                      "tz": "Пришлите ваш часовой пояс числом от UTC, например <code>3</code> для Москвы."}[sub]
            await _edit(cb, prompt, kb([[("Отмена", "p:ar")]]))
            return await cb.answer()
        text, markup = await ar_screen(db, uid)
        await _edit(cb, text, markup)
    elif a == "mutes":
        ms = await db.mutes(uid)
        rows = [[(f"🔊 {m['name']}", f"p:um:{m['chat']}")] for m in ms] + [BACK]
        await _edit(cb, "🔇 <b>Заглушённые</b>\n" + ("Нажмите на имя, чтобы снять мут." if ms else "Никого. Заглушить: <code>.mute</code> в чате с человеком."), kb(rows))
    elif a == "um":
        await db.unmute(uid, int(parts[2]))
        cb.data = "p:mutes"
        return await on_panel(cb, bot, db)
    elif a == "trg":
        sub = parts[2] if len(parts) > 2 else None
        if sub == "add":
            AWAIT[uid] = {"what": "trg_kw"}
            await _edit(cb, "Пришлите слово или фразу-триггер (ищется в сообщениях собеседников, без учёта регистра).", kb([[("Отмена", "p:trg")]]))
            return await cb.answer()
        if sub == "del":
            await db.del_trigger(uid, int(parts[3]))
        ts = await db.triggers(uid)
        rows = [[(f"🗑 {t['keyword']}", f"p:trg:del:{t['id']}")] for t in ts] + [[("➕ Добавить", "p:trg:add")], BACK]
        body = "\n".join(f"• <b>{esc(t['keyword'])}</b> → {esc(t['reply'][:60])}" for t in ts) or "Пока нет."
        await _edit(cb, f"🔑 <b>Триггеры</b>\nЕсли собеседник написал слово — бот ответит за вас.\n\n{body}", kb(rows))
    elif a == "tpl":
        if len(parts) > 3:
            await db.del_template(uid, parts[3])
        ts = await db.templates(uid)
        rows = [[(f"🗑 {t['name']}", f"p:tpl:del:{t['name']}")] for t in ts] + [BACK]
        body = "\n".join(f"• <code>.t {esc(t['name'])}</code> — {esc(t['text'][:60])}" for t in ts) or "Пока нет. Создать: <code>.tset имя текст</code> в любом чате."
        await _edit(cb, f"📝 <b>Шаблоны</b>\n{body}", kb(rows))
    elif a == "rem":
        if len(parts) > 3:
            await db.del_reminder(uid, int(parts[3]))
        import time
        rs = await db.reminders(uid)
        rows = [[(f"🗑 {r['text'][:30]}", f"p:rem:del:{r['id']}")] for r in rs] + [BACK]
        body = "\n".join(f"• через {fmt_duration(max(r['due'] - int(time.time()), 0))}: {esc(r['text'])}" for r in rs) or "Нет активных. Создать: <code>.remind 30м текст</code>"
        await _edit(cb, f"⏰ <b>Напоминания</b>\n{body}", kb(rows))
    elif a == "stats":
        rows = await db.stats(uid)
        body = "\n".join(f"{i}. {esc(r['name'] or r['chat'])} — {r['theirs']} / {r['mine']}" for i, r in enumerate(rows, 1)) or "Пока пусто."
        await _edit(cb, f"📊 <b>Активность чатов</b> (их / ваши сообщения)\n{body}", kb([BACK]))
    elif a == "help":
        await _edit(cb, HELP, kb([BACK]))
    await cb.answer()


@router.message(F.chat.type == "private", F.text, ~F.text.startswith("/"))
async def on_text(m: Message, db: Database) -> None:
    uid = m.from_user.id
    st = AWAIT.get(uid)
    if not st:
        text, markup = await home(db, uid)
        return await m.answer(text, reply_markup=markup)
    what, val = st["what"], m.text.strip()
    if what == "ar_text":
        await db.set(uid, "ar_text", val[:500])
        AWAIT.pop(uid)
        text, markup = await ar_screen(db, uid)
        return await m.answer("✅ Текст сохранён.\n\n" + text, reply_markup=markup)
    if what == "ar_sched":
        a, _, b = val.replace("—", "-").replace("–", "-").partition("-")
        if parse_hhmm(a) is None or parse_hhmm(b) is None:
            return await m.answer("Не понял. Формат: <code>22:00-09:00</code>")
        await db.set(uid, "ar_from", a.strip())
        await db.set(uid, "ar_to", b.strip())
        await db.set(uid, "ar_mode", "schedule")
        AWAIT.pop(uid)
        text, markup = await ar_screen(db, uid)
        return await m.answer("✅ Расписание сохранено.\n\n" + text, reply_markup=markup)
    if what == "ar_tz":
        try:
            tz = int(val.replace("+", ""))
            assert -12 <= tz <= 14
        except (ValueError, AssertionError):
            return await m.answer("Нужно число от -12 до 14, например <code>3</code>")
        await db.set(uid, "tz", str(tz))
        AWAIT.pop(uid)
        text, markup = await ar_screen(db, uid)
        return await m.answer("✅ Часовой пояс сохранён.\n\n" + text, reply_markup=markup)
    if what == "trg_kw":
        st.update(what="trg_reply", kw=val[:80])
        return await m.answer(f"Триггер «{esc(val[:80])}». Теперь пришлите текст ответа ({{name}} — имя собеседника).")
    if what == "trg_reply":
        await db.add_trigger(uid, st["kw"], val[:500])
        AWAIT.pop(uid)
        await m.answer("✅ Триггер добавлен.", reply_markup=kb([[("🔑 К триггерам", "p:trg")]]))
