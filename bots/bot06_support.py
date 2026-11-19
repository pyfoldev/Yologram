"""БОТ №6 — СЛУЖБА ПОДДЕРЖКИ (пересылка «пользователь ↔ админ»).

Как работает:
  • пользователь пишет боту — сообщение копируется админам;
  • админ отвечает (Reply) на это сообщение — ответ уходит пользователю.
Связь «сообщение у админа → пользователь» хранится в SQLite.

Запуск:  python -m bots.bot06_support    (нужен ADMIN_IDS в .env)
Как расширить: добавьте статусы тикетов, теги, историю переписки в таблицу.
"""
from aiogram import Bot, F, Router
from aiogram.filters import CommandStart
from aiogram.types import Message

from .core import Database, admin_ids, is_admin, run_bot

router = Router()

SCHEMA = """
CREATE TABLE IF NOT EXISTS relay (
    admin_chat INTEGER NOT NULL,   -- чат админа
    admin_msg  INTEGER NOT NULL,   -- id скопированного сообщения у админа
    user_id    INTEGER NOT NULL,   -- автор исходного обращения
    PRIMARY KEY (admin_chat, admin_msg)
);
"""
db = Database("support", SCHEMA)


@router.message(CommandStart())
async def start(message: Message) -> None:
    if is_admin(message.from_user.id):
        await message.answer("Вы админ. Отвечайте (Reply) на обращения — ответ уйдёт пользователю.")
    else:
        await message.answer("Здравствуйте! Опишите проблему — мы ответим здесь же. 💬")


@router.message(F.reply_to_message, F.from_user.id.func(is_admin))
async def admin_reply(message: Message, bot: Bot) -> None:
    """Админ ответил на пересланное обращение -> отправляем автору."""
    row = db.one(
        "SELECT user_id FROM relay WHERE admin_chat = ? AND admin_msg = ?",
        (message.chat.id, message.reply_to_message.message_id),
    )
    if row is None:
        await message.answer("Не нашёл, кому это адресовано. Отвечайте на само обращение.")
        return
    # copy_message работает с любым типом: текст, фото, документ, голосовое...
    await bot.copy_message(row["user_id"], message.chat.id, message.message_id)
    await message.answer("✅ Отправлено")


@router.message(~F.from_user.id.func(is_admin))
async def user_message(message: Message, bot: Bot) -> None:
    """Обращение пользователя -> копия каждому админу."""
    if not admin_ids():
        await message.answer("Поддержка пока не настроена (нет ADMIN_IDS).")
        return
    for admin in admin_ids():
        copy = await bot.copy_message(admin, message.chat.id, message.message_id)
        db.execute(
            "INSERT OR REPLACE INTO relay VALUES (?, ?, ?)",
            (admin, copy.message_id, message.from_user.id),
        )
        # Подпись отдельным сообщением: copy_message не показывает, кто автор.
        sig = await bot.send_message(
            admin,
            f"↑ от {message.from_user.full_name} (id {message.from_user.id})",
            reply_to_message_id=copy.message_id,
        )
        db.execute("INSERT OR REPLACE INTO relay VALUES (?, ?, ?)", (admin, sig.message_id, message.from_user.id))
    await message.answer("Принято! Ответим как можно скорее ✅")


if __name__ == "__main__":
    run_bot(router)
