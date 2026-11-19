"""БОТ №5 — АНКЕТА / ЗАЯВКА (FSM — машина состояний).

Чему учит:
  • StatesGroup: бот «помнит», на каком шаге пользователь;
  • валидация ответов (возраст — число, телефон — по шаблону);
  • отправка готовой заявки админам.

Запуск:  python -m bots.bot05_form      (нужен ADMIN_IDS в .env)
Как расширить: добавьте State в Form, хендлер шага и строку в итоговый текст.
"""
import re

from aiogram import Bot, F, Router
from aiogram.filters import Command, CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import (
    KeyboardButton,
    Message,
    ReplyKeyboardMarkup,
    ReplyKeyboardRemove,
)

from .core import admin_ids, run_bot

router = Router()


class Form(StatesGroup):
    """Шаги анкеты по порядку."""

    name = State()
    age = State()
    phone = State()
    confirm = State()


YES_NO = ReplyKeyboardMarkup(
    keyboard=[[KeyboardButton(text="✅ Отправить"), KeyboardButton(text="❌ Отмена")]],
    resize_keyboard=True,
    one_time_keyboard=True,
)


@router.message(CommandStart())
async def start(message: Message, state: FSMContext) -> None:
    await state.clear()  # на случай, если раньше уже что-то заполняли
    await state.set_state(Form.name)  # переходим на первый шаг
    await message.answer("Заполним заявку (отмена: /cancel).\n\nКак вас зовут?")


@router.message(Command("cancel"))
async def cancel(message: Message, state: FSMContext) -> None:
    await state.clear()
    await message.answer("Заявка отменена. Начать заново: /start", reply_markup=ReplyKeyboardRemove())


@router.message(Form.name, F.text)  # сработает только на шаге name
async def get_name(message: Message, state: FSMContext) -> None:
    await state.update_data(name=message.text.strip())  # сохраняем ответ
    await state.set_state(Form.age)
    await message.answer("Сколько вам лет?")


@router.message(Form.age, F.text)
async def get_age(message: Message, state: FSMContext) -> None:
    if not message.text.isdigit() or not (5 <= int(message.text) <= 120):
        await message.answer("Введите возраст числом от 5 до 120.")
        return  # остаёмся на том же шаге
    await state.update_data(age=int(message.text))
    await state.set_state(Form.phone)
    await message.answer("Ваш телефон (например, +79991234567)?")


@router.message(Form.phone, F.text)
async def get_phone(message: Message, state: FSMContext) -> None:
    if not re.fullmatch(r"\+?\d{10,15}", message.text.replace(" ", "")):
        await message.answer("Не похоже на номер. Пример: +79991234567")
        return
    await state.update_data(phone=message.text.strip())
    data = await state.get_data()
    await state.set_state(Form.confirm)
    await message.answer(summary(data) + "\n\nОтправить?", reply_markup=YES_NO)


def summary(data: dict) -> str:
    """Текст заявки — отдельная функция, чтобы переиспользовать и юзеру, и админам."""
    return (
        "📋 <b>Заявка</b>\n"
        f"Имя: {data['name']}\nВозраст: {data['age']}\nТелефон: {data['phone']}"
    )


@router.message(Form.confirm, F.text == "✅ Отправить")
async def confirm(message: Message, state: FSMContext, bot: Bot) -> None:
    data = await state.get_data()
    for admin in admin_ids():
        await bot.send_message(admin, summary(data) + f"\nОт: @{message.from_user.username or message.from_user.id}")
    await state.clear()
    await message.answer("Спасибо! Заявка отправлена 🙌", reply_markup=ReplyKeyboardRemove())


@router.message(Form.confirm)
async def confirm_other(message: Message, state: FSMContext) -> None:
    # Любой другой ответ на шаге подтверждения считаем отменой.
    await cancel(message, state)


if __name__ == "__main__":
    run_bot(router)
