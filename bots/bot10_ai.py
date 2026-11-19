"""БОТ №10 — ИИ-АССИСТЕНТ на Claude (с памятью диалога).

Чему учит:
  • вызов Claude API (anthropic SDK) из асинхронного бота;
  • хранение истории диалога для каждого пользователя;
  • индикатор «печатает…» и разбивка длинных ответов (лимит Telegram — 4096 символов).

Запуск:  python -m bots.bot10_ai    (нужен ANTHROPIC_API_KEY в .env)
Как расширить: поменяйте SYSTEM_PROMPT — получите бота-переводчика, юриста, тренера...
"""
import os

from aiogram import F, Router
from aiogram.filters import Command, CommandStart
from aiogram.types import Message
from anthropic import APIError, AsyncAnthropic

from .core import run_bot

router = Router()

# Роль и стиль ассистента. Это главный «рычаг» для превращения в другого бота.
SYSTEM_PROMPT = "Ты дружелюбный ассистент в Telegram. Отвечай кратко и по делу, на языке собеседника."
MAX_HISTORY = 20  # сколько последних сообщений помнить (экономит токены)
TG_LIMIT = 4000  # безопасный размер одного сообщения Telegram

history: dict[int, list[dict]] = {}  # user_id -> [{"role": ..., "content": ...}]
_client: AsyncAnthropic | None = None


def client() -> AsyncAnthropic:
    """Клиент создаём лениво: бот запустится и без ключа, а ошибка будет понятной."""
    global _client
    if _client is None:
        _client = AsyncAnthropic()  # ключ берётся из ANTHROPIC_API_KEY
    return _client


def split_text(text: str, size: int = TG_LIMIT) -> list[str]:
    """Режет длинный ответ на части, стараясь резать по переводам строк."""
    parts = []
    while len(text) > size:
        cut = text.rfind("\n", 0, size)
        cut = cut if cut > 0 else size
        parts.append(text[:cut])
        text = text[cut:].lstrip("\n")
    return parts + [text] if text else parts


async def ask_claude(messages: list[dict]) -> str:
    response = await client().messages.create(
        model=os.getenv("ANTHROPIC_MODEL", "claude-sonnet-5-5"),
        max_tokens=1024,
        system=SYSTEM_PROMPT,
        messages=messages,
    )
    return "".join(block.text for block in response.content if block.type == "text")


@router.message(CommandStart())
async def start(message: Message) -> None:
    await message.answer("Привет! Я ИИ-ассистент. Просто напишите мне вопрос.\n/reset — начать диалог заново.")


@router.message(Command("reset"))
async def reset(message: Message) -> None:
    history.pop(message.from_user.id, None)
    await message.answer("Память очищена 🧹")


@router.message(F.text)
async def chat(message: Message) -> None:
    if not os.getenv("ANTHROPIC_API_KEY"):
        await message.answer("⚠️ Не задан ANTHROPIC_API_KEY.")
        return
    msgs = history.setdefault(message.from_user.id, [])
    msgs.append({"role": "user", "content": message.text})
    del msgs[:-MAX_HISTORY]  # оставляем только последние N
    # API требует, чтобы диалог начинался с реплики пользователя.
    while msgs and msgs[0]["role"] != "user":
        msgs.pop(0)

    await message.bot.send_chat_action(message.chat.id, "typing")  # «печатает…»
    try:
        answer = await ask_claude(msgs)
    except APIError as e:
        msgs.pop()  # неудачный вопрос не засоряет историю
        await message.answer(f"⚠️ Ошибка ИИ-сервиса: {e.__class__.__name__}. Попробуйте позже.")
        return
    msgs.append({"role": "assistant", "content": answer})
    # parse_mode=None: ответ модели может содержать символы < >, которые сломают HTML-разметку.
    for chunk in split_text(answer or "…"):
        await message.answer(chunk, parse_mode=None)


if __name__ == "__main__":
    run_bot(router)
