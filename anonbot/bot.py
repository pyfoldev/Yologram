"""Анонимный чат-бот для Telegram: поиск собеседника, звонки, игры."""
import asyncio
import logging
import os
import time
from pathlib import Path

if Path(".env").exists():  # простейшая загрузка .env
    for line in Path(".env").read_text().splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())

from aiogram import Bot, Dispatcher, F, Router
from aiogram.exceptions import TelegramBadRequest, TelegramForbiddenError
from aiogram.filters import Command, CommandStart
from aiogram.types import (CallbackQuery, KeyboardButton, Message,
                           ReplyKeyboardMarkup)
from aiogram.utils.keyboard import InlineKeyboardBuilder

import games
import state
import web
from state import (BTN_CALL, BTN_END, BTN_NEXT, BTN_SEARCH, BTN_SETTINGS,
                   BTN_STOP_SEARCH, BTN_VIDEO, db, last, partner,
                   pending_call, queue)

log = logging.getLogger("anonbot")
router = Router()
RELAX_AFTER = 25  # сек: через сколько ослабляем фильтр интересов
INTERESTS = ["Музыка", "Кино", "Игры", "Спорт", "Аниме", "Путешествия",
             "Книги", "IT", "Арт", "Еда"]


def kb(rows):
    return ReplyKeyboardMarkup(
        keyboard=[[KeyboardButton(text=t) for t in r] for r in rows], resize_keyboard=True)


IDLE_KB = kb([[BTN_SEARCH], [BTN_SETTINGS]])
SEARCH_KB = kb([[BTN_STOP_SEARCH]])
CHAT_KB = kb([[BTN_NEXT, BTN_END], [BTN_CALL, BTN_VIDEO], [state.BTN_GAMES]])

HELP = (
    "Я соединяю двух незнакомцев для анонимного общения. Никто не видит ни имени, ни username.\n\n"
    "/search — найти собеседника\n"
    "/next — следующий собеседник\n"
    "/stop — завершить диалог / остановить поиск\n"
    "/settings — пол и интересы для поиска\n"
    "/call — аудиозвонок, /video — видеозвонок (по ссылке)\n"
    "/games — мини-игры с собеседником"
)


async def send(uid, text, **kw):
    try:
        return await state.bot.send_message(uid, text, **kw)
    except (TelegramForbiddenError, TelegramBadRequest):
        return None


# ---------- поиск и соединение ----------

def compatible(a: int, b: int, strict: bool):
    """Возвращает счёт совпадения (>=0) или None, если пара не подходит."""
    pa, pb = db.get(a), db.get(b)
    if pa["want"] != "any" and pa["want"] != pb["gender"]:
        return None
    if pb["want"] != "any" and pb["want"] != pa["gender"]:
        return None
    common = len(pa["interests"] & pb["interests"])
    if strict:
        need = (pa["by_interests"] and pa["interests"]) or (pb["by_interests"] and pb["interests"])
        if need and not common:
            return None
    return common


def find_match(uid: int, strict: bool):
    best, best_score = None, -1
    for cand in queue:  # очередь упорядочена по времени входа
        if cand == uid or last.get(uid) == cand:
            continue
        score = compatible(uid, cand, strict)
        if score is not None and score > best_score:
            best, best_score = cand, score
    return best


async def connect(a: int, b: int):
    for u in (a, b):
        queue.pop(u, None)
    partner[a], partner[b] = b, a
    common = db.get(a)["interests"] & db.get(b)["interests"]
    text = "✅ Собеседник найден! Пиши — он тебя не видит. Приятного общения."
    if common:
        text += "\nОбщие интересы: " + ", ".join(sorted(common))
    ra = await send(a, text, reply_markup=CHAT_KB)
    rb = await send(b, text, reply_markup=CHAT_KB)
    if ra is None or rb is None:  # кто-то заблокировал бота
        await end_chat(a if ra is not None else b, notify=False)
        for u, r in ((a, ra), (b, rb)):
            if r is not None:
                await send(u, "Собеседник недоступен. Попробуй ещё раз.", reply_markup=IDLE_KB)


async def end_chat(uid: int, notify=True):
    b = partner.pop(uid, None)
    if b is None:
        return None
    partner.pop(b, None)
    last[uid], last[b] = b, uid
    pair = frozenset((uid, b))
    games.drop_pair(uid, b)
    pending_call.pop(uid, None)
    pending_call.pop(b, None)
    await web.close_pair(pair)
    if notify:
        await send(b, "💔 Собеседник завершил диалог.\n/search — найти нового.", reply_markup=IDLE_KB)
    return b


async def do_search(uid: int):
    if uid in partner:
        await send(uid, "Ты уже в диалоге. «⏭ Следующий» — сменить собеседника.")
        return
    if uid in queue:
        await send(uid, "Уже ищу… ⏳", reply_markup=SEARCH_KB)
        return
    queue[uid] = time.time()
    cand = find_match(uid, strict=True)
    if cand:
        await connect(uid, cand)
        return
    p = db.get(uid)
    extra = ""
    if p["by_interests"] and p["interests"]:
        extra = f"\nИнтересы: {', '.join(sorted(p['interests']))} (если долго никого — расширю поиск)"
    await send(uid, "🔎 Ищу собеседника…" + extra, reply_markup=SEARCH_KB)


async def relax_loop():
    while True:
        await asyncio.sleep(5)
        now = time.time()
        for uid in list(queue):
            if uid in queue and now - queue[uid] > RELAX_AFTER:
                cand = find_match(uid, strict=False)
                if cand:
                    await connect(uid, cand)


async def do_stop(uid: int):
    if uid in partner:
        await end_chat(uid)
        await send(uid, "Диалог завершён. /search — найти нового.", reply_markup=IDLE_KB)
    elif queue.pop(uid, None) is not None:
        await send(uid, "Поиск остановлен.", reply_markup=IDLE_KB)
    else:
        await send(uid, "Ты сейчас ни с кем не говоришь.", reply_markup=IDLE_KB)


async def do_next(uid: int):
    if uid in partner:
        await end_chat(uid)
    await do_search(uid)


# ---------- команды ----------

@router.message(CommandStart())
async def cmd_start(m: Message):
    db.get(m.from_user.id)
    await m.answer("👋 Привет!\n\n" + HELP + "\n\nСначала можешь указать пол и интересы в /settings.",
                   reply_markup=IDLE_KB)


@router.message(Command("help"))
async def cmd_help(m: Message):
    await m.answer(HELP)


@router.message(Command("search"))
@router.message(F.text == BTN_SEARCH)
async def cmd_search(m: Message):
    await do_search(m.from_user.id)


@router.message(Command("next"))
@router.message(F.text == BTN_NEXT)
async def cmd_next(m: Message):
    await do_next(m.from_user.id)


@router.message(Command("stop", "end"))
@router.message(F.text.in_({BTN_END, BTN_STOP_SEARCH}))
async def cmd_stop(m: Message):
    await do_stop(m.from_user.id)


# ---------- настройки ----------

GENDER = {"m": "👨 Парень", "f": "👩 Девушка"}
WANT = {"any": "🎲 Любой", "m": "👨 Парня", "f": "👩 Девушку"}


def settings_view(uid: int):
    p = db.get(uid)
    text = (f"⚙️ Настройки поиска\n\nЯ: {GENDER.get(p['gender'], 'не указан')}\n"
            f"Ищу: {WANT[p['want']]}\n"
            f"Интересы: {', '.join(sorted(p['interests'])) or 'не выбраны'}\n"
            f"Поиск по интересам: {'вкл' if p['by_interests'] else 'выкл'}")
    b = InlineKeyboardBuilder()
    for k, v in GENDER.items():
        b.button(text=("✓ " if p["gender"] == k else "") + "Я " + v.split()[1].lower(), callback_data=f"st:g:{k}")
    for k, v in WANT.items():
        b.button(text=("✓ " if p["want"] == k else "") + "Ищу: " + v.split()[-1].lower(), callback_data=f"st:w:{k}")
    for i, name in enumerate(INTERESTS):
        b.button(text=("✓ " if name in p["interests"] else "") + name, callback_data=f"st:i:{i}")
    b.button(text=("✅ " if p["by_interests"] else "⬜ ") + "Искать по интересам", callback_data="st:bi")
    b.button(text="Готово", callback_data="st:done")
    b.adjust(2, 3, 3, 2, 2, 2, 1, 1)
    return text, b.as_markup()


@router.message(Command("settings"))
@router.message(F.text == BTN_SETTINGS)
async def cmd_settings(m: Message):
    text, markup = settings_view(m.from_user.id)
    await m.answer(text, reply_markup=markup)


@router.callback_query(F.data.startswith("st:"))
async def cb_settings(cb: CallbackQuery):
    uid, parts = cb.from_user.id, cb.data.split(":")
    p = db.get(uid)
    if parts[1] == "g":
        db.set(uid, gender=parts[2])
    elif parts[1] == "w":
        db.set(uid, want=parts[2])
    elif parts[1] == "i":
        name = INTERESTS[int(parts[2])]
        db.set(uid, interests=p["interests"] ^ {name})
    elif parts[1] == "bi":
        db.set(uid, by_interests=not p["by_interests"])
    elif parts[1] == "done":
        await cb.message.delete()
        await cb.answer("Сохранено")
        return
    text, markup = settings_view(uid)
    try:
        await cb.message.edit_text(text, reply_markup=markup)
    except TelegramBadRequest:
        pass
    await cb.answer()


# ---------- звонки ----------

async def request_call(uid: int, video: bool):
    b = partner.get(uid)
    if b is None:
        await send(uid, "Звонить можно только в диалоге.")
        return
    if not os.environ.get("BASE_URL"):
        await send(uid, "Звонки не настроены (нет BASE_URL).")
        return
    if b in pending_call or any(v[0] == uid for v in pending_call.values()):
        await send(uid, "Запрос на звонок уже отправлен.")
        return
    pending_call[b] = (uid, video)
    kind = "видеозвонок 🎥" if video else "аудиозвонок 📞"
    k = InlineKeyboardBuilder()
    k.button(text="✅ Принять", callback_data="call:y")
    k.button(text="❌ Отклонить", callback_data="call:n")
    await send(b, f"Собеседник предлагает {kind} через браузер.\n"
                  "⚠️ Звонок идёт напрямую — собеседник теоретически может увидеть твой IP.",
               reply_markup=k.as_markup())
    await send(uid, "Запрос на звонок отправлен ⏳")


@router.message(Command("call"))
@router.message(F.text == BTN_CALL)
async def cmd_call(m: Message):
    await request_call(m.from_user.id, False)


@router.message(Command("video"))
@router.message(F.text == BTN_VIDEO)
async def cmd_video(m: Message):
    await request_call(m.from_user.id, True)


@router.callback_query(F.data == "call:y")
async def call_yes(cb: CallbackQuery):
    uid = cb.from_user.id
    p = pending_call.pop(uid, None)
    if not p or partner.get(uid) != p[0]:
        await cb.answer("Запрос устарел", show_alert=True)
        return
    caller, video = p
    room = web.create_room(video, frozenset((uid, caller)))
    url = f"{os.environ['BASE_URL'].rstrip('/')}/call/{room}"
    k = InlineKeyboardBuilder()
    k.button(text="📞 Открыть звонок", url=url)
    text = "Звонок готов. Открой ссылку (разреши доступ к микрофону" + (" и камере" if video else "") + "):"
    for u in (uid, caller):
        if await send(u, text, reply_markup=k.as_markup()) is None:
            await send(u, f"{text}\n{url}")
    try:
        await cb.message.edit_text("Ты принял звонок.")
    except TelegramBadRequest:
        pass
    await cb.answer()


@router.callback_query(F.data == "call:n")
async def call_no(cb: CallbackQuery):
    p = pending_call.pop(cb.from_user.id, None)
    try:
        await cb.message.edit_text("Ты отклонил звонок.")
    except TelegramBadRequest:
        pass
    if p:
        await send(p[0], "Собеседник отклонил звонок.")
    await cb.answer()


# ---------- пересылка сообщений ----------

@router.message(F.chat.type == "private", ~F.text.startswith("/"))
@router.message(F.chat.type == "private", F.text.is_(None))
async def relay(m: Message):
    uid = m.from_user.id
    b = partner.get(uid)
    if b is None:
        if uid in queue:
            await m.answer("Ещё ищу собеседника… ⏳")
        else:
            await m.answer("Ты не в диалоге. Нажми «🔍 Искать».", reply_markup=IDLE_KB)
        return
    try:
        await m.copy_to(b)
    except TelegramForbiddenError:
        await end_chat(uid, notify=False)
        await m.answer("Собеседник покинул бота. Диалог завершён.", reply_markup=IDLE_KB)
    except TelegramBadRequest:
        await m.answer("Не удалось передать это сообщение.")


async def main():
    logging.basicConfig(level=logging.INFO)
    state.bot = bot = Bot(os.environ["BOT_TOKEN"])
    dp = Dispatcher()
    dp.include_router(games.router)
    dp.include_router(router)
    await web.start(os.environ.get("WEB_HOST", "0.0.0.0"), int(os.environ.get("WEB_PORT", "8080")))
    asyncio.create_task(relax_loop())
    await bot.delete_webhook()
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
