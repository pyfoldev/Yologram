"""Мини-игры между двумя собеседниками."""
import asyncio
import random

from aiogram import F, Router
from aiogram.exceptions import TelegramBadRequest, TelegramForbiddenError
from aiogram.filters import Command
from aiogram.types import CallbackQuery, InlineKeyboardMarkup, Message
from aiogram.utils.keyboard import InlineKeyboardBuilder

import state
from state import BTN_GAMES, partner, pending_game

router = Router()

GAMES = {
    "ttt": "❌⭕ Крестики-нолики",
    "rps": "✊✌️✋ Камень-ножницы-бумага",
    "dice": "🎲 Кости",
    "td": "🎭 Правда или действие",
}
QUESTIONS = [
    "Правда: какой твой самый странный страх?",
    "Правда: что бы ты сделал(а), выиграв миллион?",
    "Правда: какой фильм ты можешь смотреть бесконечно?",
    "Правда: какая у тебя самая необычная привычка?",
    "Правда: куда бы ты поехал(а) завтра, если бы мог(ла)?",
    "Правда: какой навык ты хотел(а) бы получить мгновенно?",
    "Правда: самое смешное, что с тобой случалось?",
    "Действие: опиши свой день тремя смайликами.",
    "Действие: придумай прозвище для собеседника.",
    "Действие: расскажи шутку — худшую, какую знаешь.",
    "Действие: назови 5 животных на букву «К» за 10 секунд.",
    "Действие: отправь голосовое, где ты поёшь куплет любой песни.",
    "Действие: опиши любимую еду, не называя её.",
    "Действие: придумай историю из трёх предложений про кота-космонавта.",
]
LINES = [(0, 1, 2), (3, 4, 5), (6, 7, 8), (0, 3, 6), (1, 4, 7), (2, 5, 8), (0, 4, 8), (2, 4, 6)]
RPS = {"r": "✊ Камень", "s": "✌️ Ножницы", "p": "✋ Бумага"}
BEATS = {"r": "s", "s": "p", "p": "r"}


class Game:
    def __init__(self, kind, a, b):
        self.kind, self.players = kind, (a, b)
        self.msgs: dict[int, int] = {}
        self.over = False
        self.d: dict = {}

    def other(self, uid):
        return self.players[1] if uid == self.players[0] else self.players[0]


games: dict[int, Game] = {}


def drop_pair(a: int, b: int):
    for u in (a, b):
        games.pop(u, None)
        pending_game.pop(u, None)


def menu_kb() -> InlineKeyboardMarkup:
    kb = InlineKeyboardBuilder()
    for k, name in GAMES.items():
        kb.button(text=name, callback_data=f"gm:i:{k}")
    kb.adjust(1)
    return kb.as_markup()


async def safe(coro):
    try:
        return await coro
    except (TelegramBadRequest, TelegramForbiddenError):
        return None


# ---------- меню и приглашения ----------

@router.message(Command("games"))
@router.message(F.text == BTN_GAMES)
async def games_menu(m: Message):
    if m.from_user.id not in partner:
        await m.answer("Игры доступны только в диалоге. Нажми «🔍 Искать».")
        return
    await m.answer("Во что сыграем с собеседником?", reply_markup=menu_kb())


@router.callback_query(F.data.startswith("gm:i:"))
async def invite(cb: CallbackQuery):
    uid, kind = cb.from_user.id, cb.data.split(":")[2]
    b = partner.get(uid)
    if b is None or kind not in GAMES:
        await cb.answer("Ты не в диалоге", show_alert=True)
        return
    if uid in games or b in games:
        await cb.answer("Сначала закончите текущую игру", show_alert=True)
        return
    pending_game[b] = (uid, kind)
    kb = InlineKeyboardBuilder()
    kb.button(text="✅ Играть", callback_data="gm:y")
    kb.button(text="❌ Не хочу", callback_data="gm:n")
    await safe(state.bot.send_message(
        b, f"🎮 Собеседник предлагает сыграть:\n{GAMES[kind]}", reply_markup=kb.as_markup()))
    await safe(cb.message.edit_text(f"Приглашение отправлено: {GAMES[kind]} ⏳"))
    await cb.answer()


@router.callback_query(F.data == "gm:y")
async def accept(cb: CallbackQuery):
    uid = cb.from_user.id
    p = pending_game.pop(uid, None)
    if not p or partner.get(uid) != p[0] or uid in games or p[0] in games:
        await cb.answer("Приглашение устарело", show_alert=True)
        await safe(cb.message.edit_reply_markup(reply_markup=None))
        return
    await safe(cb.message.edit_text("Игра начинается!"))
    await cb.answer()
    await start_game(p[1], p[0], uid)


@router.callback_query(F.data == "gm:n")
async def decline(cb: CallbackQuery):
    p = pending_game.pop(cb.from_user.id, None)
    await safe(cb.message.edit_text("Ты отказался от игры."))
    if p:
        await safe(state.bot.send_message(p[0], "Собеседник отказался от игры."))
    await cb.answer()


@router.callback_query(F.data == "gm:end")
async def end(cb: CallbackQuery):
    uid = cb.from_user.id
    g = games.get(uid)
    if g:
        for u in g.players:
            games.pop(u, None)
            if u in g.msgs:
                await safe(state.bot.edit_message_reply_markup(
                    chat_id=u, message_id=g.msgs[u], reply_markup=None))
            if u != uid:
                await safe(state.bot.send_message(u, "Собеседник закончил игру."))
    await safe(cb.message.edit_reply_markup(reply_markup=None))
    await cb.answer("Игра окончена")


@router.callback_query(F.data == "noop")
async def noop(cb: CallbackQuery):
    await cb.answer()


# ---------- запуск и отрисовка ----------

async def start_game(kind, a, b):
    g = Game(kind, a, b)
    games[a] = games[b] = g
    if kind == "ttt":
        g.d = {"board": [""] * 9, "turn": a, "sym": {a: "❌", b: "⭕"}, "result": None}
    elif kind == "rps":
        g.d = {"choice": {}}
    elif kind == "td":
        g.d = {"q": random.choice(QUESTIONS)}
    elif kind == "dice":
        await run_dice(g)
        return
    await refresh(g, first=True)


def again_row(kb: InlineKeyboardBuilder, g: Game):
    kb.button(text="🔁 Ещё раз", callback_data=f"gm:i:{g.kind}")
    kb.button(text="🎮 Другая игра", callback_data="gm:menu")


@router.callback_query(F.data == "gm:menu")
async def menu_cb(cb: CallbackQuery):
    await cb.message.answer("Во что сыграем?", reply_markup=menu_kb())
    await cb.answer()


def render(g: Game, uid: int):
    kb = InlineKeyboardBuilder()
    if g.kind == "ttt":
        d = g.d
        for i in range(9):
            kb.button(text=d["board"][i] or "⬜",
                      callback_data="noop" if g.over else f"ttt:{i}")
        kb.adjust(3)
        if g.over:
            text = "🤝 Ничья!" if d["result"] == "draw" else (
                "🏆 Ты выиграл(а)!" if d["result"] == uid else "😔 Ты проиграл(а)")
            again_row(kb, g)
            kb.adjust(3, 3, 3, 2)
        else:
            text = f"Крестики-нолики. Ты — {d['sym'][uid]}\n" + (
                "👉 Твой ход" if d["turn"] == uid else "⏳ Ход собеседника")
            kb.button(text="⏹ Закончить", callback_data="gm:end")
            kb.adjust(3, 3, 3, 1)
    elif g.kind == "rps":
        mine = g.d["choice"].get(uid)
        if g.over:
            a, b = g.d["choice"][uid], g.d["choice"][g.other(uid)]
            res = "🤝 Ничья!" if a == b else ("🏆 Ты выиграл(а)!" if BEATS[a] == b else "😔 Ты проиграл(а)")
            text = f"Ты: {RPS[a]}\nСобеседник: {RPS[b]}\n\n{res}"
            again_row(kb, g)
            kb.adjust(2)
        elif mine:
            text = f"Ты выбрал: {RPS[mine]}\n⏳ Ждём собеседника…"
        else:
            text = "Камень, ножницы, бумага — выбирай!"
            for k, v in RPS.items():
                kb.button(text=v, callback_data=f"rps:{k}")
            kb.button(text="⏹ Закончить", callback_data="gm:end")
            kb.adjust(3, 1)
    else:  # td
        text = f"🎭 Правда или действие\n\n{g.d['q']}"
        kb.button(text="🔁 Ещё", callback_data="td:next")
        kb.button(text="⏹ Закончить", callback_data="gm:end")
        kb.adjust(2)
    return text, kb.as_markup()


async def refresh(g: Game, first=False):
    for u in g.players:
        text, kb = render(g, u)
        if first or u not in g.msgs:
            m = await safe(state.bot.send_message(u, text, reply_markup=kb))
            if m:
                g.msgs[u] = m.message_id
        else:
            await safe(state.bot.edit_message_text(
                text, chat_id=u, message_id=g.msgs[u], reply_markup=kb))


def finish(g: Game):
    g.over = True
    for u in g.players:
        if games.get(u) is g:
            games.pop(u)


# ---------- игры ----------

@router.callback_query(F.data.startswith("ttt:"))
async def ttt_move(cb: CallbackQuery):
    uid = cb.from_user.id
    g = games.get(uid)
    if not g or g.kind != "ttt" or g.over:
        await cb.answer("Игра не идёт")
        return
    d, i = g.d, int(cb.data.split(":")[1])
    if d["turn"] != uid:
        await cb.answer("Сейчас не твой ход")
        return
    if d["board"][i]:
        await cb.answer("Клетка занята")
        return
    d["board"][i] = d["sym"][uid]
    if any(all(d["board"][x] == d["sym"][uid] for x in ln) for ln in LINES):
        d["result"] = uid
        finish(g)
    elif all(d["board"]):
        d["result"] = "draw"
        finish(g)
    else:
        d["turn"] = g.other(uid)
    await cb.answer()
    await refresh(g)


@router.callback_query(F.data.startswith("rps:"))
async def rps_pick(cb: CallbackQuery):
    uid = cb.from_user.id
    g = games.get(uid)
    if not g or g.kind != "rps" or g.over or uid in g.d["choice"]:
        await cb.answer("Нельзя")
        return
    g.d["choice"][uid] = cb.data.split(":")[1]
    if len(g.d["choice"]) == 2:
        finish(g)
    await cb.answer()
    await refresh(g)


@router.callback_query(F.data == "td:next")
async def td_next(cb: CallbackQuery):
    g = games.get(cb.from_user.id)
    if not g or g.kind != "td":
        await cb.answer("Игра не идёт")
        return
    g.d["q"] = random.choice([q for q in QUESTIONS if q != g.d["q"]])
    await cb.answer()
    await refresh(g)


async def run_dice(g: Game):
    a, b = g.players
    bot = state.bot
    for u in g.players:
        await safe(bot.send_message(u, "🎲 Бросаем кости…"))
    ma = await safe(bot.send_dice(a))
    mb = await safe(bot.send_dice(b))
    if not ma or not mb:
        finish(g)
        return
    # показать каждому кубик соперника
    await safe(bot.copy_message(b, a, ma.message_id))
    await safe(bot.copy_message(a, b, mb.message_id))
    await asyncio.sleep(4)
    if games.get(a) is not g:  # диалог завершили
        return
    va, vb = ma.dice.value, mb.dice.value
    finish(g)
    for u, mine, theirs in ((a, va, vb), (b, vb, va)):
        res = "🤝 Ничья!" if mine == theirs else ("🏆 Ты выиграл(а)!" if mine > theirs else "😔 Ты проиграл(а)")
        kb = InlineKeyboardBuilder()
        again_row(kb, g)
        kb.adjust(2)
        await safe(bot.send_message(u, f"Ты: {mine}\nСобеседник: {theirs}\n\n{res}",
                                    reply_markup=kb.as_markup()))
