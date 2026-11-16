from __future__ import annotations

import random

SIZE = 25
MINES = 5
EDGE = 0.97


def new_field() -> list[bool]:
    f = [False] * SIZE
    for i in random.sample(range(SIZE), MINES):
        f[i] = True
    return f


def multiplier(safe_opened: int) -> float:
    m = 1.0
    for i in range(safe_opened):
        m *= (SIZE - i) / (SIZE - MINES - i)
    return round(m * EDGE, 2) if safe_opened else 1.0
