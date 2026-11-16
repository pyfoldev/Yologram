"""Мини-игры с кнопками: в личке с ботом (против бота) и PvP через инлайн-режим."""
from __future__ import annotations

import asyncio
import random
import time

from aiogram import Bot, F, Router
from aiogram.exceptions import TelegramAPIError
from aiogram.filters import Command, CommandObject
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

from nori.db import Database
from nori.games import blackjack as bj
from nori.games import mines, quiz, ttt
from nori.games.rps import CHOICES, ICONS, judge
from nori.games.slots import slots_payout
from nori.utils import esc

router = Router(name="games")

STATE: dict[tuple, dict] = {}
MAX_STATE = 3000
BETS = (10, 50, 100)
SYM = {" ": "⬜", "X": "❌", "O": "⭕"}


def kb(rows: list[list[tuple[str, str]]]) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[InlineKeyboardButton(text=t, callback_data=d) for t, d in r] for r in rows])


MENU_BTN = [("🎮 Меню", "g:menu")]


async def menu_text(db: Database, uid: int, name: str) -> str:
    return f"🎮 <b>Мини-игры Nori</b>\nМонет у вас: <b>{await db.balance(uid, name)}</b> 🪙\nВыберите игру:"


def menu_kb() -> InlineKeyboardMarkup:
    return kb([
        [("❌⭕ Крестики-нолики", "g:new:ttt"), ("✊ Камень-ножницы", "g:new:rps")],
        [("🃏 Блэкджек", "g:new:bj"), ("💣 Сапёр", "g:new:mines")],
        [("🔢 Угадай число", "g:new:guess"), ("🧠 Викторина", "g:new:quiz")],
        [("🪙 Орёл/решка", "g:new:coin"), ("🎰 Слоты", "g:new:slots")],
        [("🎁 Бонус", "g:bonus"), ("🏆 Топ", "g:top")],
        [("⚔️ Игра с другом — в любом чате: @имя_бота", "g:pvp")],
    ])


def _key(cb: CallbackQuery) -> tuple:
    return ("i", cb.inline_message_id) if cb.inline_message_id else (cb.message.chat.id, cb.message.message_id)


def _put(cb: CallbackQuery, state: dict) -> dict:
    if len(STATE) > MAX_STATE:
        for k, _ in sorted(STATE.items(), key=lambda kv: kv[1].get("ts", 0))[:MAX_STATE // 3]:
            STATE.pop(k, None)
    state["ts"] = time.time()
    STATE[_key(cb)] = state
    return state


async def show(cb: CallbackQuery, text: str, markup: InlineKeyboardMarkup | None = None) -> None:
    try:
        if cb.inline_message_id:
            await cb.bot.edit_message_text(text, inline_message_id=cb.inline_message_id, reply_markup=markup)
        else:
            await cb.bot.edit_message_text(text, chat_id=cb.message.chat.id, message_id=cb.message.message_id, reply_markup=markup)
    except TelegramAPIError:
        pass


@router.message(Command("games", "game"))
async def cmd_games(m: Message, db: Database) -> None:
    await m.answer(await menu_text(db, m.from_user.id, m.from_user.full_name), reply_markup=menu_kb())


@router.message(Command("balance"))
async def cmd_balance(m: Message, db: Database) -> None:
    await m.answer(f"🪙 Ваш баланс: <b>{await db.balance(m.from_user.id, m.from_user.full_name)}</b>")


@router.message(Command("bonus"))
async def cmd_bonus(m: Message, db: Database) -> None:
    got = await db.claim_bonus(m.from_user.id, int(time.time()))
    await m.answer(f"🎁 Ежедневный бонус: <b>+{got}</b> 🪙" if got else "Бонус уже получен сегодня. Приходите завтра!")


# ---------------- диспетчер ----------------
@router.callback_query(F.data.startswith("g:"), ~F.data.regexp(r"^g:(bjs|mns|cnb|slb|cn):"))
async def on_game(cb: CallbackQuery, bot: Bot, db: Database) -> None:
    parts = cb.data.split(":")
    action = parts[1]
    uid, name = cb.from_user.id, cb.from_user.full_name
    if action == "noop":
        return await cb.answer()
    if action in ("ptt", "prps"):
        return await (_pvp_ttt if action == "ptt" else _pvp_rps)(cb, parts[2])
    if action == "pvp":
        return await cb.answer("Напишите в любом чате @" + (await bot.me()).username + " и выберите игру — "
                               "соперник нажмёт «Вступить».", show_alert=True)
    if action == "menu":
        await show(cb, await menu_text(db, uid, name), menu_kb())
        return await cb.answer()
    if action == "bonus":
        got = await db.claim_bonus(uid, int(time.time()))
        return await cb.answer(f"🎁 +{got} монет!" if got else "Бонус уже получен сегодня", show_alert=True)
    if action == "top":
        rows = await db.top()
        txt = "🏆 <b>Богатейшие игроки</b>\n" + "\n".join(f"{i}. {esc(n)} — {c} 🪙" for i, (n, c) in enumerate(rows, 1))
        await show(cb, txt, kb([MENU_BTN]))
        return await cb.answer()
    if action == "new":
        return await NEW[parts[2]](cb, db)
    handler = PRESS.get(action)
    if handler is None:
        return await cb.answer()
    st = STATE.get(_key(cb))
    if st is None:
        await cb.answer("Игра устарела — начните новую", show_alert=True)
        return await show(cb, "Игра устарела.", kb([MENU_BTN]))
    st["ts"] = time.time()
    await handler(cb, db, st, parts[2:])


async def _bet_screen(cb: CallbackQuery, db: Database, title: str, prefix: str) -> None:
    bal = await db.balance(cb.from_user.id, cb.from_user.full_name)
    await show(cb, f"{title}\nБаланс: <b>{bal}</b> 🪙\nВыберите ставку:",
               kb([[(f"{b} 🪙", f"{prefix}:{b}") for b in BETS], MENU_BTN]))
    await cb.answer()


async def _spend(cb: CallbackQuery, db: Database, bet: int) -> bool:
    if bet not in BETS or not await db.try_spend(cb.from_user.id, bet):
        await cb.answer("Не хватает монет. Заберите /bonus", show_alert=True)
        return False
    return True


# ---------------- крестики-нолики ----------------
def ttt_kb(board, done=False) -> InlineKeyboardMarkup:
    rows = [[(SYM[board[r * 3 + c]], f"g:ttt:{r * 3 + c}") for c in range(3)] for r in range(3)]
    if done:
        rows.append([("🔁 Ещё раз", "g:new:ttt"), *MENU_BTN])
    return kb(rows)


async def new_ttt(cb, db):
    _put(cb, {"game": "ttt", "b": ttt.new_board()})
    await show(cb, "❌⭕ <b>Крестики-нолики</b>\nВы — ❌. Ваш ход!", ttt_kb(ttt.new_board()))
    await cb.answer()


async def press_ttt(cb, db, st, a):
    if st.get("game") != "ttt":
        return await cb.answer()
    b = st["b"]
    if not ttt.move(b, int(a[0]), "X"):
        return await cb.answer("Клетка занята")
    w = ttt.winner(b)
    if w is None:
        ttt.move(b, ttt.bot_move(b), "O")
        w = ttt.winner(b)
    if w is None:
        await show(cb, "❌⭕ <b>Крестики-нолики</b>\nВаш ход!", ttt_kb(b))
        return await cb.answer()
    STATE.pop(_key(cb), None)
    if w == "X":
        await db.add_coins(cb.from_user.id, 15); res = "🎉 Вы победили! <b>+15</b> 🪙"
    elif w == "O":
        res = "😔 Бот победил."
    else:
        await db.add_coins(cb.from_user.id, 5); res = "🤝 Ничья. <b>+5</b> 🪙"
    await show(cb, f"❌⭕ <b>Крестики-нолики</b>\n{res}", ttt_kb(b, True))
    await cb.answer()


# ---------------- камень-ножницы-бумага ----------------
def rps_kb() -> InlineKeyboardMarkup:
    return kb([[(f"{ICONS[c]} {c.capitalize()}", f"g:rps:{c}") for c in CHOICES]])


async def new_rps(cb, db):
    _put(cb, {"game": "rps", "p": 0, "b": 0})
    await show(cb, "✊✌️🖐 <b>Камень-ножницы-бумага</b>\nИграем до 3 побед. Счёт 0:0\nВаш выбор:", rps_kb())
    await cb.answer()


async def press_rps(cb, db, st, a):
    if st.get("game") != "rps" or a[0] not in CHOICES:
        return await cb.answer()
    mine, theirs = a[0], random.choice(CHOICES)
    r = judge(mine, theirs)
    st["p"] += r == 1
    st["b"] += r == -1
    line = f"{ICONS[mine]} vs {ICONS[theirs]} — " + ("вы выиграли раунд" if r == 1 else "бот выиграл раунд" if r == -1 else "ничья")
    if st["p"] == 3 or st["b"] == 3:
        STATE.pop(_key(cb), None)
        win = st["p"] == 3
        if win:
            await db.add_coins(cb.from_user.id, 10)
        await show(cb, f"✊✌️🖐 {line}\nИтог {st['p']}:{st['b']} — " + ("🎉 победа! <b>+10</b> 🪙" if win else "😔 проиграли."),
                   kb([[("🔁 Ещё раз", "g:new:rps"), *MENU_BTN]]))
    else:
        await show(cb, f"✊✌️🖐 {line}\nСчёт {st['p']}:{st['b']} (до 3)\nВаш выбор:", rps_kb())
    await cb.answer()


# ---------------- блэкджек ----------------
def _hand(h) -> str:
    return " ".join(h)


def bj_text(st, reveal=False, note="") -> str:
    d = _hand(st["d"]) if reveal else f"{st['d'][0]} 🂠"
    dv = f" ({bj.value(st['d'])})" if reveal else ""
    return (f"🃏 <b>Блэкджек</b> · ставка {st['bet']} 🪙\nДилер: {d}{dv}\n"
            f"Вы: {_hand(st['p'])} ({bj.value(st['p'])})" + (f"\n\n{note}" if note else ""))


async def new_bj(cb, db):
    await _bet_screen(cb, db, "🃏 <b>Блэкджек</b>", "g:bjs")


async def start_bj(cb: CallbackQuery, db: Database, bet: int) -> None:
    if not await _spend(cb, db, bet):
        return
    deck = bj.new_deck()
    st = _put(cb, {"game": "bj", "deck": deck, "p": [deck.pop(), deck.pop()], "d": [deck.pop(), deck.pop()], "bet": bet})
    if bj.is_blackjack(st["p"]):
        return await _bj_finish(cb, db, st)
    await show(cb, bj_text(st), kb([[("➕ Ещё", "g:bj:hit"), ("✋ Хватит", "g:bj:stand")]]))
    await cb.answer()


async def _bj_finish(cb, db, st):
    if bj.value(st["p"]) <= 21 and not bj.is_blackjack(st["p"]):
        bj.dealer_play(st["deck"], st["d"])
    res, back = bj.settle(st["p"], st["d"], st["bet"])
    STATE.pop(_key(cb), None)
    if back:
        await db.add_coins(cb.from_user.id, back)
    note = {"bust": "💥 Перебор! Ставка проиграна.", "blackjack": f"🎉 Блэкджек! <b>+{back - st['bet']}</b> 🪙",
            "win": f"🎉 Победа! <b>+{back - st['bet']}</b> 🪙", "push": "🤝 Ничья, ставка возвращена.",
            "lose": "😔 Дилер выиграл."}[res]
    await show(cb, bj_text(st, True, note), kb([[("🔁 Ещё раз", "g:new:bj"), *MENU_BTN]]))
    await cb.answer()


async def press_bj(cb, db, st, a):
    if st.get("game") != "bj":
        return await cb.answer()
    if a[0] == "hit":
        st["p"].append(st["deck"].pop())
        if bj.value(st["p"]) >= 21:
            return await _bj_finish(cb, db, st)
        await show(cb, bj_text(st), kb([[("➕ Ещё", "g:bj:hit"), ("✋ Хватит", "g:bj:stand")]]))
        return await cb.answer()
    await _bj_finish(cb, db, st)


# ---------------- сапёр ----------------
def mines_kb(st, reveal=False) -> InlineKeyboardMarkup:
    rows = []
    for r in range(5):
        row = []
        for c in range(5):
            i = r * 5 + c
            if i in st["open"]:
                row.append(("💎", "g:noop"))
            elif reveal:
                row.append(("💣" if st["f"][i] else "▫️", "g:noop"))
            else:
                row.append(("⬛", f"g:mn:{i}"))
        rows.append(row)
    if reveal:
        rows.append([("🔁 Ещё раз", "g:new:mines"), *MENU_BTN])
    else:
        n = len(st["open"])
        rows.append([(f"💰 Забрать {int(st['bet'] * mines.multiplier(n))} 🪙 (×{mines.multiplier(n)})", "g:mn:cash")] if n else [("Откройте клетку — внутри 💎 или 💣", "g:noop")])
    return kb(rows)


async def new_mines(cb, db):
    await _bet_screen(cb, db, f"💣 <b>Сапёр</b> — 5 мин на поле 5×5. Каждый 💎 увеличивает выигрыш.", "g:mns")


async def start_mines(cb, db, bet):
    if not await _spend(cb, db, bet):
        return
    st = _put(cb, {"game": "mines", "f": mines.new_field(), "open": set(), "bet": bet})
    await show(cb, f"💣 <b>Сапёр</b> · ставка {bet} 🪙", mines_kb(st))
    await cb.answer()


async def press_mines(cb, db, st, a):
    if st.get("game") != "mines":
        return await cb.answer()
    if a[0] == "cash":
        if not st["open"]:
            return await cb.answer()
        win = int(st["bet"] * mines.multiplier(len(st["open"])))
        await db.add_coins(cb.from_user.id, win)
        STATE.pop(_key(cb), None)
        await show(cb, f"💣 <b>Сапёр</b>\n💰 Вы забрали <b>{win}</b> 🪙 (ставка {st['bet']})", mines_kb(st, True))
        return await cb.answer()
    i = int(a[0])
    if i in st["open"]:
        return await cb.answer()
    if st["f"][i]:
        STATE.pop(_key(cb), None)
        await show(cb, f"💣 <b>Бабах!</b> Ставка {st['bet']} 🪙 проиграна.", mines_kb(st, True))
        return await cb.answer("💥")
    st["open"].add(i)
    if len(st["open"]) == mines.SIZE - mines.MINES:
        return await press_mines(cb, db, st, ["cash"])
    await show(cb, f"💣 <b>Сапёр</b> · ставка {st['bet']} 🪙\nОткрыто 💎: {len(st['open'])}", mines_kb(st))
    await cb.answer()


# ---------------- угадай число ----------------
GUESS_MAX, GUESS_TRIES = 30, 5


def guess_kb(st, done=False) -> InlineKeyboardMarkup:
    rows = []
    for r in range(5):
        rows.append([(str(n) if st["lo"] <= n <= st["hi"] and not done else "·", f"g:gs:{n}" if st["lo"] <= n <= st["hi"] and not done else "g:noop")
                     for n in range(r * 6 + 1, r * 6 + 7)])
    if done:
        rows.append([("🔁 Ещё раз", "g:new:guess"), *MENU_BTN])
    return kb(rows)


async def new_guess(cb, db):
    st = _put(cb, {"game": "guess", "n": random.randint(1, GUESS_MAX), "left": GUESS_TRIES, "lo": 1, "hi": GUESS_MAX})
    await show(cb, f"🔢 <b>Угадай число</b> от 1 до {GUESS_MAX}. Попыток: {GUESS_TRIES}", guess_kb(st))
    await cb.answer()


async def press_guess(cb, db, st, a):
    if st.get("game") != "guess":
        return await cb.answer()
    n = int(a[0])
    if n == st["n"]:
        STATE.pop(_key(cb), None)
        await db.add_coins(cb.from_user.id, 15)
        await show(cb, f"🎉 Верно, это <b>{n}</b>! <b>+15</b> 🪙", guess_kb(st, True))
        return await cb.answer()
    st["left"] -= 1
    hint = "больше ⬆️" if n < st["n"] else "меньше ⬇️"
    if n < st["n"]:
        st["lo"] = max(st["lo"], n + 1)
    else:
        st["hi"] = min(st["hi"], n - 1)
    if st["left"] <= 0:
        STATE.pop(_key(cb), None)
        await show(cb, f"😔 Попытки кончились. Загадано было <b>{st['n']}</b>.", guess_kb(st, True))
        return await cb.answer()
    await show(cb, f"🔢 Число {hint}\nПопыток: {st['left']}", guess_kb(st))
    await cb.answer()


# ---------------- викторина ----------------
def quiz_screen(st, note="") -> tuple[str, InlineKeyboardMarkup]:
    q, opts, _ = quiz.QUESTIONS[st["qs"][st["i"]]]
    text = (f"{note}\n\n" if note else "") + f"🧠 <b>Вопрос {st['i'] + 1}/{len(st['qs'])}</b>\n{esc(q)}"
    return text, kb([[(o, f"g:qz:{k}")] for k, o in enumerate(opts)])


async def new_quiz(cb, db):
    st = _put(cb, {"game": "quiz", "qs": random.sample(range(len(quiz.QUESTIONS)), 5), "i": 0, "score": 0})
    text, markup = quiz_screen(st)
    await show(cb, text, markup)
    await cb.answer()


async def press_quiz(cb, db, st, a):
    if st.get("game") != "quiz":
        return await cb.answer()
    q, opts, right = quiz.QUESTIONS[st["qs"][st["i"]]]
    ok = int(a[0]) == right
    st["score"] += ok
    note = "✅ Верно!" if ok else f"❌ Неверно. Правильно: {esc(opts[right])}"
    st["i"] += 1
    if st["i"] >= len(st["qs"]):
        STATE.pop(_key(cb), None)
        reward = st["score"] * 5
        if reward:
            await db.add_coins(cb.from_user.id, reward)
        await show(cb, f"{note}\n\n🏁 Итог: <b>{st['score']}/{len(st['qs'])}</b>" + (f" · <b>+{reward}</b> 🪙" if reward else ""),
                   kb([[("🔁 Ещё раз", "g:new:quiz"), *MENU_BTN]]))
    else:
        text, markup = quiz_screen(st, note)
        await show(cb, text, markup)
    await cb.answer()


# ---------------- орёл/решка ----------------
async def new_coin(cb, db):
    await _bet_screen(cb, db, "🪙 <b>Орёл или решка</b> — выигрыш ×2", "g:cnb")


async def pick_coin(cb, db, bet):
    if bet not in BETS:
        return await cb.answer()
    await show(cb, f"🪙 Ставка {bet} 🪙. На что ставите?",
               kb([[("🦅 Орёл", f"g:cn:{bet}:h"), ("🪙 Решка", f"g:cn:{bet}:t")], MENU_BTN]))
    await cb.answer()


# ---------------- слоты ----------------
async def new_slots(cb, db):
    await _bet_screen(cb, db, "🎰 <b>Слоты</b> — 777 даёт ×10, три одинаковых ×5", "g:slb")


async def play_slots(cb: CallbackQuery, db: Database, bet: int) -> None:
    if not await _spend(cb, db, bet):
        return
    await cb.answer()
    chat_id = cb.message.chat.id
    try:
        dice = await cb.bot.send_dice(chat_id, emoji="🎰")
    except TelegramAPIError:
        await db.add_coins(cb.from_user.id, bet)
        return
    await asyncio.sleep(2.5)
    net = slots_payout(dice.dice.value, bet)
    if net > 0:
        await db.add_coins(cb.from_user.id, bet + net)
        text = f"🎉 Выигрыш <b>+{net}</b> 🪙"
    else:
        text = f"😔 Проигрыш <b>-{bet}</b> 🪙"
    bal = await db.balance(cb.from_user.id)
    await cb.bot.send_message(chat_id, f"{text}\nБаланс: {bal} 🪙", reply_markup=kb([[(f"🔁 Ещё {bet} 🪙", f"g:slb:{bet}"), *MENU_BTN]]))


NEW = {"ttt": new_ttt, "rps": new_rps, "bj": new_bj, "mines": new_mines, "guess": new_guess, "quiz": new_quiz,
       "coin": new_coin, "slots": new_slots}


# ставки/старты не требуют состояния: отдельные обработчики
@router.callback_query(F.data.regexp(r"^g:(bjs|mns|cnb|slb):\d+$"))
async def on_bet(cb: CallbackQuery, db: Database) -> None:
    _, kind, bet = cb.data.split(":")
    bet = int(bet)
    if kind == "bjs":
        await start_bj(cb, db, bet)
    elif kind == "mns":
        await start_mines(cb, db, bet)
    elif kind == "cnb":
        await pick_coin(cb, db, bet)
    else:
        await play_slots(cb, db, bet)


@router.callback_query(F.data.regexp(r"^g:cn:\d+:[ht]$"))
async def on_coin(cb: CallbackQuery, db: Database) -> None:
    _, _, bet, side = cb.data.split(":")
    bet = int(bet)
    if not await _spend(cb, db, bet):
        return
    res = random.choice("ht")
    win = res == side
    if win:
        await db.add_coins(cb.from_user.id, bet * 2)
    await show(cb, f"🪙 Выпало: <b>{'🦅 Орёл' if res == 'h' else '🪙 Решка'}</b>\n" +
               (f"🎉 Выигрыш <b>+{bet}</b> 🪙" if win else f"😔 Проигрыш <b>-{bet}</b> 🪙"),
               kb([[(f"🔁 Ещё {bet} 🪙", f"g:cnb:{bet}"), *MENU_BTN]]))
    await cb.answer()


PRESS = {"ttt": press_ttt, "rps": press_rps, "bj": press_bj, "mn": press_mines, "gs": press_guess, "qz": press_quiz}


# ---------------- PvP (инлайн) ----------------
def _pvp_state(cb: CallbackQuery, game: str) -> dict:
    st = STATE.get(_key(cb))
    if st is None or st.get("game") != game:
        st = _put(cb, {"game": game, "p": [(cb.from_user.id, cb.from_user.full_name)]})
        if game == "ptt":
            st["b"] = ttt.new_board()
        else:
            st["c"] = {}
        st["fresh"] = True
    return st


def ptt_text(st, note="") -> str:
    x, o = st["p"][0][1], st["p"][1][1]
    turn = "❌" if st["turn"] == "X" else "⭕"
    return f"❌ {esc(x)} vs ⭕ {esc(o)}\n" + (note or f"Ходит {turn}")


async def _pvp_ttt(cb: CallbackQuery, arg: str) -> None:
    st = _pvp_state(cb, "ptt")
    uid = cb.from_user.id
    if len(st["p"]) < 2:
        if arg != "join":
            return await cb.answer()
        if st.pop("fresh", False) or uid == st["p"][0][0]:
            st.pop("fresh", None)
            await show(cb, f"❌⭕ <b>Крестики-нолики</b>\n{esc(st['p'][0][1])} ждёт соперника…", kb([[("⚔️ Вступить", "g:ptt:join")]]))
            return await cb.answer("Ждём соперника")
        st["p"].append((uid, cb.from_user.full_name))
        st["turn"] = "X"
        await show(cb, ptt_text(st), _ptt_kb(st["b"]))
        return await cb.answer()
    if arg == "join":
        return await cb.answer("Игра уже идёт")
    mark = "X" if uid == st["p"][0][0] else "O" if uid == st["p"][1][0] else None
    if mark is None:
        return await cb.answer("Вы не участвуете в этой игре", show_alert=True)
    if mark != st["turn"]:
        return await cb.answer("Сейчас не ваш ход")
    if not ttt.move(st["b"], int(arg), mark):
        return await cb.answer("Клетка занята")
    w = ttt.winner(st["b"])
    if w:
        STATE.pop(_key(cb), None)
        note = "🤝 Ничья!" if w == "draw" else f"🎉 Победил {esc(st['p'][0][1] if w == 'X' else st['p'][1][1])} ({SYM[w]})!"
        await show(cb, ptt_text(st, note), _ptt_kb(st["b"], True))
        return await cb.answer()
    st["turn"] = "O" if mark == "X" else "X"
    await show(cb, ptt_text(st), _ptt_kb(st["b"]))
    await cb.answer()


def _ptt_kb(board, done=False) -> InlineKeyboardMarkup:
    return kb([[(SYM[board[r * 3 + c]], "g:noop" if done else f"g:ptt:{r * 3 + c}") for c in range(3)] for r in range(3)])


async def _pvp_rps(cb: CallbackQuery, arg: str) -> None:
    st = _pvp_state(cb, "prps")
    uid = cb.from_user.id
    choose = kb([[(f"{ICONS[c]}", f"g:prps:{c}") for c in CHOICES]])
    if len(st["p"]) < 2:
        if arg != "join":
            return await cb.answer()
        if st.pop("fresh", False) or uid == st["p"][0][0]:
            st.pop("fresh", None)
            await show(cb, f"✊✌️🖐 <b>Камень-ножницы-бумага</b>\n{esc(st['p'][0][1])} ждёт соперника…", kb([[("⚔️ Вступить", "g:prps:join")]]))
            return await cb.answer("Ждём соперника")
        st["p"].append((uid, cb.from_user.full_name))
        await show(cb, f"✊✌️🖐 {esc(st['p'][0][1])} vs {esc(st['p'][1][1])}\nОба выбирают втайне:", choose)
        return await cb.answer()
    if arg == "join":
        return await cb.answer("Игра уже идёт")
    if uid not in (st["p"][0][0], st["p"][1][0]):
        return await cb.answer("Вы не участвуете в этой игре", show_alert=True)
    if arg not in CHOICES:
        return await cb.answer()
    st["c"][uid] = arg
    if len(st["c"]) < 2:
        return await cb.answer(f"Вы выбрали {ICONS[arg]}. Ждём соперника")
    (u1, n1), (u2, n2) = st["p"]
    r = judge(st["c"][u1], st["c"][u2])
    STATE.pop(_key(cb), None)
    res = "🤝 Ничья!" if r == 0 else f"🎉 Победил {esc(n1 if r == 1 else n2)}!"
    await show(cb, f"✊✌️🖐 {esc(n1)} {ICONS[st['c'][u1]]} vs {ICONS[st['c'][u2]]} {esc(n2)}\n{res}")
    await cb.answer()
