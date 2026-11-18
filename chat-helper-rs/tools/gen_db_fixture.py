"""Создаёт tests/fixtures/py.db оригинальным Python-кодом: python tools/gen_db_fixture.py <корень бота> <выход.db>"""
import asyncio, os, sys
sys.path.insert(0, sys.argv[1])
from bot.db import Database
from bot.games.garden import new_garden

async def main(out):
    if os.path.exists(out): os.remove(out)
    db = await Database.connect(out)
    await db.change_balance(1, 10, 500); await db.try_spend(1, 10, 200)
    await db.add_warn(1, 10, 1000, None); await db.add_warn(1, 10, 1000, None)
    await db.set_rank(1, 10, 3, 1, 5)
    await db.set_setting(1, "warn_limit", 5); await db.set_setting(1, "links_mode", "all")
    await db.remember_user(10, "@Vasya", "Вася")
    for _ in range(5): await db.record_message(1, 10, 7200)
    for _ in range(2): await db.record_message(1, 11, 7200)
    await db.flush_messages()
    await db.save_garden(1, 10, new_garden())
    await db.create_captcha("tok", 1, 10, ["a", "b"], 99, 5)
    await db.conn.execute("PRAGMA wal_checkpoint(TRUNCATE)"); await db.conn.commit()
    await db.close()
asyncio.run(main(sys.argv[2]))
