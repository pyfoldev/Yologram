"""Выплаты слотов Telegram (🎰, значения 1..64)."""

JACKPOT = 64          # 777
TRIPLES = (1, 22, 43)  # BAR, виноград, лимон


def slots_payout(dice_value: int, bet: int) -> int:
    """Чистый результат ставки: +выигрыш или -bet при проигрыше."""
    if dice_value == JACKPOT:
        return bet * 9   # возврат ×10
    if dice_value in TRIPLES:
        return bet * 4   # возврат ×5
    return -bet
