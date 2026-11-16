import pytest

from nori.games import blackjack as bj, mines, ttt
from nori.games.rps import judge
from nori.games.slots import slots_payout
from nori.utils import calc, fmt_duration, in_window, mock, parse_duration, parse_hhmm
from nori.handlers.commands import parse
from nori.emoji_inject import inject_html, load_map


def test_duration():
    assert parse_duration("30м") == 1800 and parse_duration("2h") == 7200 and parse_duration("1д") == 86400
    assert parse_duration("45") == 2700 and parse_duration("0") is None and parse_duration("abc") is None
    assert fmt_duration(5400) == "1 ч 30 мин"


def test_calc_safe():
    assert calc("2+2*3") == "8" and calc("10/4") == "2.5" and calc("(1+2)×3") == "9" and calc("2^10") == "1024"
    for bad in ("__import__('os')", "1/0", "2**99999", "a+1", "[1]"):
        with pytest.raises((ValueError, SyntaxError, ZeroDivisionError)):
            calc(bad)


def test_window_over_midnight():
    s, e = parse_hhmm("22:00"), parse_hhmm("09:00")
    assert in_window(23 * 60, s, e) and in_window(3 * 60, s, e) and not in_window(12 * 60, s, e)
    assert in_window(10 * 60, parse_hhmm("09:00"), parse_hhmm("18:00"))


def test_command_parse():
    assert parse(".mute 1ч") == ("mute", "1ч") and parse(".ТЕСТ") is None and parse("привет") is None
    assert parse(".help") == ("help", "") and parse("...") is None


def test_ttt_ai_never_loses_hard():
    import random
    for _ in range(30):
        b = ttt.new_board()
        while ttt.winner(b) is None:
            ttt.move(b, random.choice([i for i, c in enumerate(b) if c == " "]), "X")
            if ttt.winner(b) is None:
                ttt.move(b, ttt.bot_move(b, mistake=0), "O")
        assert ttt.winner(b) != "X"


def test_blackjack():
    assert bj.value(["A♠", "K♥"]) == 21 and bj.value(["A♠", "A♥", "9♦"]) == 21 and bj.value(["K♠", "Q♥", "5♦"]) == 25
    assert bj.settle(["A♠", "K♥"], ["10♠", "9♥"], 100) == ("blackjack", 250)
    assert bj.settle(["10♠", "9♥"], ["10♦", "9♣"], 100) == ("push", 100)
    assert bj.settle(["K♠", "Q♥", "5♦"], ["2♦", "3♣"], 100)[1] == 0


def test_mines_and_slots_and_rps():
    assert mines.multiplier(0) == 1.0 and mines.multiplier(3) > mines.multiplier(1) > 1
    assert slots_payout(64, 10) == 90 and slots_payout(1, 10) == 40 and slots_payout(2, 10) == -10
    assert judge("камень", "ножницы") == 1 and judge("бумага", "ножницы") == -1 and judge("камень", "камень") == 0


def test_premium_emoji_map():
    emap = load_map()
    assert len(emap) > 200
    out = inject_html("Готово ✅ <code>✅</code>", emap)
    assert out.count("<tg-emoji") == 1
