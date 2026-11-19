"""БОТ №3 — СПИСОК ДЕЛ (TODO) С БАЗОЙ ДАННЫХ.

Чему учит:
  • команды с аргументами (/add купить хлеб);
  • SQLite через core.Database: у каждого пользователя свой список;
  • инлайн-кнопки «выполнить» и «удалить» в каждом сообщении.

Запуск:  python -m bots.bot03_todo
Как расширить: добавьте колонку (например due_date) в SCHEMA и поле в хендлеры.
"""
from aiogram import F, Router
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

from .core import Database, run_bot

router = Router()

SCHEMA = """
CREATE TABLE IF NOT EXISTS tasks (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    text    TEXT    NOT NULL,
    done    INTEGER NOT NULL DEFAULT 0
);
"""
db = Database("todo", SCHEMA)

HELP = (
    "📝 <b>Список дел</b>\n\n"
    "/add <i>текст</i> — добавить задачу\n"
    "/list — показать задачи\n"
    "/clear — удалить выполненные"
)


def task_kb(task_id: int, done: bool) -> InlineKeyboardMarkup:
    toggle = "↩️ Вернуть" if done else "✅ Готово"
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(text=toggle, callback_data=f"toggle:{task_id}"),
                InlineKeyboardButton(text="🗑 Удалить", callback_data=f"del:{task_id}"),
            ]
        ]
    )


def task_text(row) -> str:
    return f"{'✅' if row['done'] else '⬜️'} {row['text']}"


@router.message(CommandStart())
async def start(message: Message) -> None:
    await message.answer(HELP)


@router.message(Command("add"))
async def add_task(message: Message, command: CommandObject) -> None:
    # command.args — всё, что написано после /add
    if not command.args:
        await message.answer("Напишите задачу: /add купить молоко")
        return
    task_id = db.execute(
        "INSERT INTO tasks (user_id, text) VALUES (?, ?)",
        (message.from_user.id, command.args.strip()),
    )
    row = db.one("SELECT * FROM tasks WHERE id = ?", (task_id,))
    await message.answer(task_text(row), reply_markup=task_kb(task_id, False))


@router.message(Command("list"))
async def list_tasks(message: Message) -> None:
    rows = db.query("SELECT * FROM tasks WHERE user_id = ? ORDER BY id", (message.from_user.id,))
    if not rows:
        await message.answer("Список пуст. Добавьте задачу: /add текст")
        return
    for row in rows:  # по сообщению на задачу — так у каждой свои кнопки
        await message.answer(task_text(row), reply_markup=task_kb(row["id"], bool(row["done"])))


@router.message(Command("clear"))
async def clear_done(message: Message) -> None:
    db.execute("DELETE FROM tasks WHERE user_id = ? AND done = 1", (message.from_user.id,))
    await message.answer("Выполненные задачи удалены 🧹")


@router.callback_query(F.data.startswith("toggle:"))
async def toggle(call: CallbackQuery) -> None:
    task_id = int(call.data.split(":")[1])
    # В условии WHERE проверяем user_id: нельзя менять чужие задачи, подделав callback_data.
    db.execute(
        "UPDATE tasks SET done = 1 - done WHERE id = ? AND user_id = ?",
        (task_id, call.from_user.id),
    )
    row = db.one("SELECT * FROM tasks WHERE id = ? AND user_id = ?", (task_id, call.from_user.id))
    if row is None:
        await call.answer("Задача не найдена")
        return
    await call.message.edit_text(task_text(row), reply_markup=task_kb(task_id, bool(row["done"])))
    await call.answer()


@router.callback_query(F.data.startswith("del:"))
async def delete(call: CallbackQuery) -> None:
    task_id = int(call.data.split(":")[1])
    db.execute("DELETE FROM tasks WHERE id = ? AND user_id = ?", (task_id, call.from_user.id))
    await call.message.delete()
    await call.answer("Удалено")


if __name__ == "__main__":
    run_bot(router)
