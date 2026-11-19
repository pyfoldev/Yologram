"""БОТ №2 — ВИКТОРИНА.

Чему учит:
  • хранение состояния игры по пользователю (словарь в памяти);
  • callback_data с несколькими параметрами;
  • подсчёт очков и итоговый экран.

Запуск:  python -m bots.bot02_quiz
Как расширить: добавьте Question(...) в список QUESTIONS.
"""
from dataclasses import dataclass

from aiogram import F, Router
from aiogram.filters import Command, CommandStart
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

from .core import run_bot

router = Router()


@dataclass
class Question:
    text: str
    options: list[str]
    correct: int  # индекс правильного ответа в options (с нуля)


QUESTIONS = [
    Question("Столица Австралии?", ["Сидней", "Канберра", "Мельбурн"], 1),
    Question("Сколько планет в Солнечной системе?", ["7", "8", "9"], 1),
    Question("Какой язык создал Гвидо ван Россум?", ["Java", "Ruby", "Python"], 2),
    Question("Самый большой океан?", ["Атлантический", "Тихий", "Индийский"], 1),
]

# user_id -> {"q": номер текущего вопроса, "score": очки}. В памяти: после
# перезапуска игры сбросятся. Нужна постоянность — храните в core.Database.
games: dict[int, dict[str, int]] = {}


def question_kb(q_index: int) -> InlineKeyboardMarkup:
    """Кнопки вариантов. callback_data = 'ans:<номер вопроса>:<номер варианта>'."""
    q = QUESTIONS[q_index]
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text=opt, callback_data=f"ans:{q_index}:{i}")]
            for i, opt in enumerate(q.options)
        ]
    )


def question_text(q_index: int) -> str:
    return f"Вопрос {q_index + 1}/{len(QUESTIONS)}\n\n<b>{QUESTIONS[q_index].text}</b>"


@router.message(CommandStart())
@router.message(Command("quiz"))  # один хендлер — две команды
async def start_quiz(message: Message) -> None:
    games[message.from_user.id] = {"q": 0, "score": 0}
    await message.answer(question_text(0), reply_markup=question_kb(0))


@router.callback_query(F.data.startswith("ans:"))
async def on_answer(call: CallbackQuery) -> None:
    _, q_str, opt_str = call.data.split(":")
    q_index, chosen = int(q_str), int(opt_str)

    game = games.get(call.from_user.id)
    # Защита: игры нет (бот перезапускали) или нажали на кнопку старого вопроса.
    if game is None or game["q"] != q_index:
        await call.answer("Этот вопрос уже неактуален. Начните заново: /quiz", show_alert=True)
        return

    q = QUESTIONS[q_index]
    if chosen == q.correct:
        game["score"] += 1
        await call.answer("✅ Верно!")
    else:
        await call.answer(f"❌ Неверно. Правильно: {q.options[q.correct]}", show_alert=True)

    game["q"] += 1
    if game["q"] < len(QUESTIONS):
        await call.message.edit_text(question_text(game["q"]), reply_markup=question_kb(game["q"]))
    else:  # вопросы закончились — показываем результат
        total = len(QUESTIONS)
        await call.message.edit_text(
            f"🏁 Игра окончена!\nВаш результат: <b>{game['score']}/{total}</b>\n\nЕщё раз: /quiz"
        )
        del games[call.from_user.id]


if __name__ == "__main__":
    run_bot(router)
