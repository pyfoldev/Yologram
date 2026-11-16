"""Крестики-нолики: логика + ИИ (из чат-хелпера)."""
from __future__ import annotations

import random

LINES = [(0, 1, 2), (3, 4, 5), (6, 7, 8), (0, 3, 6), (1, 4, 7), (2, 5, 8), (0, 4, 8), (2, 4, 6)]


def new_board() -> list[str]:
    return [" "] * 9


def move(board: list[str], idx: int, mark: str) -> bool:
    if not 0 <= idx < 9 or board[idx] != " ":
        return False
    board[idx] = mark
    return True


def winner(board: list[str]) -> str | None:
    for a, b, c in LINES:
        if board[a] != " " and board[a] == board[b] == board[c]:
            return board[a]
    return "draw" if " " not in board else None


def _minimax(board, mark, me, other) -> int:
    w = winner(board)
    if w == me:
        return 1
    if w == other:
        return -1
    if w == "draw":
        return 0
    scores = []
    for i in range(9):
        if board[i] == " ":
            board[i] = mark
            scores.append(_minimax(board, other if mark == me else me, me, other))
            board[i] = " "
    return max(scores) if mark == me else min(scores)


def bot_move(board: list[str], me: str = "O", other: str = "X", mistake: float = 0.25) -> int:
    free = [i for i, c in enumerate(board) if c == " "]
    if random.random() < mistake:
        return random.choice(free)
    best, best_s = free[0], -2
    for i in free:
        board[i] = me
        s = _minimax(board, other, me, other)
        board[i] = " "
        if s > best_s:
            best, best_s = i, s
    return best
