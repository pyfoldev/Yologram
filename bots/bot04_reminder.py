"""БОТ №4 — НАПОМИНАНИЯ.

Чему учит:
  • фоновые задачи asyncio (sleep до нужного момента);
  • разбор пользовательского ввода («10м», «2ч», «1д»);
  • восстановление задач из БД при старте (on_startup).

Запуск:  python -m bots.bot04_reminder
Использование:  /remind 30m позвонить маме      (s — сек, m — мин, h — час, d — дней;
                также работают м, ч, д)
Как расширить: добавьте повторяющиеся напоминания — колонку repeat в SCHEMA.
"""
import asyncio
import re
import time

from aiogram import Bot, Router
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.types import Message

from .core import Database, run_bot

router = Router()

SCHEMA = """
CREATE TABLE IF NOT EXISTS reminders (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id INTEGER NOT NULL,
    text    TEXT    NOT NULL,
    fire_at REAL    NOT NULL          -- unix-время срабатывания
);
"""
db = Database("reminders", SCHEMA)

UNITS = {"s": 1, "с": 1, "m": 60, "м": 60, "h": 3600, "ч": 3600, "d": 86400, "д": 86400}


def parse_delay(token: str) -> int | None:
    """'30m' -> 1800 секунд. Возвращает None, если формат неверный."""
    m = re.fullmatch(r"(\d+)\s*([smhdсмчд])", token.lower())
    if not m:
        return None
    return int(m.group(1)) * UNITS[m.group(2)]


async def fire(bot: Bot, reminder_id: int, chat_id: int, text: str, delay: float) -> None:
    """Ждёт delay секунд, отправляет напоминание и удаляет запись."""
    await asyncio.sleep(max(delay, 0))
    # Если напоминание отменили (/cancel) пока мы спали — запись исчезла, молчим.
    if db.one("SELECT id FROM reminders WHERE id = ?", (reminder_id,)) is None:
        return
    await bot.send_message(chat_id, f"⏰ Напоминание: {text}")
    db.execute("DELETE FROM reminders WHERE id = ?", (reminder_id,))


# Ссылки на фоновые задачи нужно хранить, иначе их может собрать сборщик мусора.
_tasks: set[asyncio.Task] = set()


def schedule(bot: Bot, reminder_id: int, chat_id: int, text: str, delay: float) -> None:
    task = asyncio.create_task(fire(bot, reminder_id, chat_id, text, delay))
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)


@router.message(CommandStart())
async def start(message: Message) -> None:
    await message.answer(
        "⏰ <b>Бот-напоминалка</b>\n\n"
        "/remind <i>30m</i> <i>текст</i> — напомнить через 30 минут\n"
        "Единицы: s/с, m/м, h/ч, d/д\n"
        "/my — мои напоминания\n"
        "/cancel <i>номер</i> — отменить"
    )


@router.message(Command("remind"))
async def remind(message: Message, command: CommandObject, bot: Bot) -> None:
    parts = (command.args or "").split(maxsplit=1)
    delay = parse_delay(parts[0]) if parts else None
    if delay is None or len(parts) < 2:
        await message.answer("Формат: /remind 30m позвонить маме")
        return
    fire_at = time.time() + delay
    rid = db.execute(
        "INSERT INTO reminders (chat_id, text, fire_at) VALUES (?, ?, ?)",
        (message.chat.id, parts[1], fire_at),
    )
    schedule(bot, rid, message.chat.id, parts[1], delay)
    await message.answer(f"Хорошо, напомню через {parts[0]} (№{rid}) ✅")


@router.message(Command("my"))
async def my_reminders(message: Message) -> None:
    rows = db.query(
        "SELECT * FROM reminders WHERE chat_id = ? ORDER BY fire_at", (message.chat.id,)
    )
    if not rows:
        await message.answer("Активных напоминаний нет.")
        return
    lines = [
        f"№{r['id']} — через {max(int(r['fire_at'] - time.time()) // 60, 0)} мин: {r['text']}"
        for r in rows
    ]
    await message.answer("\n".join(lines))


@router.message(Command("cancel"))
async def cancel(message: Message, command: CommandObject) -> None:
    if not (command.args or "").strip().isdigit():
        await message.answer("Формат: /cancel 3")
        return
    db.execute(
        "DELETE FROM reminders WHERE id = ? AND chat_id = ?",
        (int(command.args), message.chat.id),
    )
    await message.answer("Отменено (если такое напоминание было).")


async def restore(bot: Bot) -> None:
    """При старте поднимаем все сохранённые напоминания — переживают перезапуск бота."""
    for r in db.query("SELECT * FROM reminders"):
        schedule(bot, r["id"], r["chat_id"], r["text"], r["fire_at"] - time.time())


if __name__ == "__main__":
    run_bot(router, on_startup=restore)
