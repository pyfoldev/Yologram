"""БОТ №7 — МОДЕРАТОР ГРУППЫ.

Что умеет:
  • удаляет сообщения со ссылками и запрещёнными словами;
  • система предупреждений: 3 варна — бан;
  • команды админов: /warn, /ban, /unwarn (ответом на сообщение).

Запуск:  python -m bots.bot07_moderator
Настройка: добавьте бота в группу и сделайте администратором
(права: удаление сообщений и блокировка участников).
Как расширить: добавьте фильтры (капс, повторы), приветствие новичков и т.д.
"""
import re

from aiogram import Bot, F, Router
from aiogram.filters import Command
from aiogram.types import Message

from .core import Database, run_bot

router = Router()
# Модерируем только групповые чаты, личку игнорируем.
router.message.filter(F.chat.type.in_({"group", "supergroup"}))

SCHEMA = "CREATE TABLE IF NOT EXISTS warns (chat_id INTEGER, user_id INTEGER, n INTEGER, PRIMARY KEY (chat_id, user_id));"
db = Database("moderator", SCHEMA)

MAX_WARNS = 3
BAD_WORDS = {"спам", "казино", "ставки"}  # дополняйте свой список
LINK_RE = re.compile(r"(https?://|t\.me/|www\.)", re.IGNORECASE)


def is_violation(text: str) -> str | None:
    """Возвращает причину нарушения или None. Чистая функция — легко тестировать."""
    if LINK_RE.search(text):
        return "ссылки запрещены"
    words = set(re.findall(r"\w+", text.lower()))
    if words & BAD_WORDS:
        return "запрещённое слово"
    return None


async def is_chat_admin(bot: Bot, chat_id: int, user_id: int) -> bool:
    member = await bot.get_chat_member(chat_id, user_id)
    return member.status in ("administrator", "creator")


def add_warn(chat_id: int, user_id: int) -> int:
    """Увеличивает счётчик предупреждений и возвращает новое значение."""
    db.execute(
        "INSERT INTO warns VALUES (?, ?, 1) ON CONFLICT(chat_id, user_id) DO UPDATE SET n = n + 1",
        (chat_id, user_id),
    )
    return db.one("SELECT n FROM warns WHERE chat_id = ? AND user_id = ?", (chat_id, user_id))["n"]


async def punish(bot: Bot, chat_id: int, user, reason: str) -> None:
    n = add_warn(chat_id, user.id)
    if n >= MAX_WARNS:
        await bot.ban_chat_member(chat_id, user.id)
        await bot.send_message(chat_id, f"🚫 {user.full_name} забанен ({n}/{MAX_WARNS}): {reason}")
    else:
        await bot.send_message(chat_id, f"⚠️ {user.full_name}, предупреждение {n}/{MAX_WARNS}: {reason}")


# --- Команды админов. Регистрируем ПЕРЕД общим фильтром, иначе его перехватит. ---
@router.message(Command("warn"), F.reply_to_message)
async def cmd_warn(message: Message, bot: Bot) -> None:
    if not await is_chat_admin(bot, message.chat.id, message.from_user.id):
        return
    await punish(bot, message.chat.id, message.reply_to_message.from_user, "решение модератора")


@router.message(Command("unwarn"), F.reply_to_message)
async def cmd_unwarn(message: Message, bot: Bot) -> None:
    if not await is_chat_admin(bot, message.chat.id, message.from_user.id):
        return
    db.execute(
        "DELETE FROM warns WHERE chat_id = ? AND user_id = ?",
        (message.chat.id, message.reply_to_message.from_user.id),
    )
    await message.answer("Предупреждения сброшены ✅")


@router.message(Command("ban"), F.reply_to_message)
async def cmd_ban(message: Message, bot: Bot) -> None:
    if not await is_chat_admin(bot, message.chat.id, message.from_user.id):
        return
    await bot.ban_chat_member(message.chat.id, message.reply_to_message.from_user.id)
    await message.answer("🚫 Пользователь забанен.")


# --- Автомодерация всех текстовых сообщений ---
@router.message(F.text)
async def auto_moderate(message: Message, bot: Bot) -> None:
    reason = is_violation(message.text)
    if not reason:
        return
    if await is_chat_admin(bot, message.chat.id, message.from_user.id):
        return  # админов не наказываем
    await message.delete()
    await punish(bot, message.chat.id, message.from_user, reason)


if __name__ == "__main__":
    run_bot(router)
