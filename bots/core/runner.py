"""Единая точка запуска: каждый бот создаёт Router, а здесь он оживает."""
import asyncio
import logging
from typing import Awaitable, Callable

from aiogram import Bot, Dispatcher, Router
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode

from .config import get_token


def make_bot(token: str | None = None) -> Bot:
    """Бот с HTML-разметкой по умолчанию (<b>жирный</b>, <i>курсив</i>, <code>код</code>)."""
    return Bot(token or get_token(), default=DefaultBotProperties(parse_mode=ParseMode.HTML))


def make_dispatcher(*routers: Router) -> Dispatcher:
    """Dispatcher принимает обновления и раздаёт их роутерам."""
    dp = Dispatcher()  # хранилище FSM по умолчанию — в памяти (сбросится при перезапуске)
    dp.include_routers(*routers)
    return dp


def run_bot(
    *routers: Router,
    on_startup: Callable[[Bot], Awaitable[None]] | None = None,
) -> None:
    """Запускает бота в режиме long polling (не нужен ни домен, ни HTTPS).

    on_startup — необязательная async-функция, вызывается один раз при старте
    (например, восстановить напоминания из базы).
    """
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    async def _main() -> None:
        bot = make_bot()
        dp = make_dispatcher(*routers)
        if on_startup:
            await on_startup(bot)
        me = await bot.get_me()
        logging.info("Бот @%s запущен. Остановка — Ctrl+C", me.username)
        await bot.delete_webhook(drop_pending_updates=True)
        await dp.start_polling(bot)

    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        logging.info("Бот остановлен")
