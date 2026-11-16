from __future__ import annotations

import random

RANKS = ["2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A"]
SUITS = "♠♥♦♣"


def new_deck() -> list[str]:
    d = [r + s for r in RANKS for s in SUITS]
    random.shuffle(d)
    return d


def value(hand: list[str]) -> int:
    total, aces = 0, 0
    for c in hand:
        r = c[:-1]
        if r == "A":
            total += 11
            aces += 1
        elif r in ("J", "Q", "K"):
            total += 10
        else:
            total += int(r)
    while total > 21 and aces:
        total -= 10
        aces -= 1
    return total


def is_blackjack(hand: list[str]) -> bool:
    return len(hand) == 2 and value(hand) == 21


def dealer_play(deck: list[str], hand: list[str]) -> None:
    while value(hand) < 17:
        hand.append(deck.pop())


def settle(player: list[str], dealer: list[str], bet: int) -> tuple[str, int]:
    """(итог, сколько вернуть игроку вместе со ставкой)."""
    p, d = value(player), value(dealer)
    if p > 21:
        return "bust", 0
    if is_blackjack(player) and not is_blackjack(dealer):
        return "blackjack", bet + bet * 3 // 2
    if d > 21 or p > d:
        return "win", bet * 2
    if p == d:
        return "push", bet
    return "lose", 0
