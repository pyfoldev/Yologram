from __future__ import annotations

import asyncio
import logging
import time

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramAPIError
from aiogram.fsm.storage.memory import MemoryStorage

from nori.config import load_config
from nori.db import Database
from nori.emoji_inject import EmojiSession
from nori.handlers import business, games, inline, panel
from nori.utils import esc


async def background(bot: Bot, db: Database) -> None:
    """Раз в 20 секунд: сработавшие напоминания; раз в час: чистка старого кэша."""
    last_prune = 0.0
    while True:
        try:
            for r in await db.due_reminders(int(time.time())):
                await db.finish_reminder(r["id"])
                try:
                    await bot.send_message(r["pm_chat"], f"⏰ <b>Напоминание</b>\n{esc(r['text'])}")
                except TelegramAPIError as e:
                    logging.warning("reminder failed: %s", e)
            if time.time() - last_prune > 3600:
                await db.prune()
                last_prune = time.time()
        except Exception:
            logging.exception("background task")
        await asyncio.sleep(20)


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = load_config()
    db = Database(cfg.db_path)
    await db.init()
    bot = Bot(cfg.bot_token, session=EmojiSession(), default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    dp = Dispatcher(storage=MemoryStorage(), db=db)
    # порядок важен: игры и бизнес-события раньше общего текстового обработчика меню
    dp.include_routers(business.router, games.router, inline.router, panel.router)
    me = await bot.get_me()
    logging.info("Nori запущен как @%s", me.username)
    task = asyncio.create_task(background(bot, db))
    try:
        await bot.delete_webhook(drop_pending_updates=False)
        await dp.start_polling(bot, allowed_updates=dp.resolve_used_update_types())
    finally:
        task.cancel()
        await db.close()
        await bot.session.close()


if __name__ == "__main__":
    asyncio.run(main())
