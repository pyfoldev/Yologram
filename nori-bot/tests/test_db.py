import time


async def test_settings_mutes_coins(env):
    db, *_ = env
    assert await db.get(1, "ar_on") == "0"
    assert await db.toggle(1, "ar_on") is True and await db.flag(1, "ar_on")
    await db.mute(1, 10, "Вася", int(time.time()) + 100)
    assert await db.is_muted(1, 10)
    await db.mute(1, 11, "Петя", int(time.time()) - 1)
    assert not await db.is_muted(1, 11)
    assert await db.unmute(1, 10) and not await db.is_muted(1, 10)
    assert await db.balance(5, "A") == 100
    assert await db.try_spend(5, 60) and not await db.try_spend(5, 60)
    assert await db.claim_bonus(5, 86400 * 10) == 100 and await db.claim_bonus(5, 86400 * 10 + 5) is None
    await db.add_trigger(1, "Цена", "100р")
    assert (await db.triggers(1))[0]["keyword"] == "цена"
    assert await db.toggle_vip(1, 3, "x") and not await db.toggle_vip(1, 3, "x")
