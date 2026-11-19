"""БОТ №1 — МЕНЮ С КНОПКАМИ (визитка / справочник).

Чему учит:
  • обычная (reply) клавиатура — кнопки внизу экрана, шлют текст;
  • инлайн-клавиатура — кнопки под сообщением, шлют callback_data;
  • навигация «страницами» через редактирование одного сообщения.

Запуск:  python -m bots.bot01_menu
Как расширить: добавьте раздел в словарь PAGES — кнопка и страница появятся сами.
"""
from aiogram import F, Router
from aiogram.filters import CommandStart
from aiogram.types import (
    CallbackQuery,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    Message,
    ReplyKeyboardMarkup,
)

from .core import run_bot

router = Router()

# ---- Содержимое: ключ -> (название кнопки, текст страницы). Правьте под себя. ----
PAGES: dict[str, tuple[str, str]] = {
    "about": ("ℹ️ О нас", "Мы делаем полезных ботов.\nДобавьте сюда своё описание."),
    "prices": ("💰 Цены", "• Базовый — 500 ₽\n• Профи — 1500 ₽"),
    "contacts": ("📞 Контакты", "Email: hello@example.com\nTelegram: @example"),
}

# Тексты кнопок нижней клавиатуры вынесены в константы, чтобы не ошибиться в фильтрах.
BTN_MENU = "📋 Меню"
BTN_HELP = "❓ Помощь"


def main_reply_kb() -> ReplyKeyboardMarkup:
    """Постоянная клавиатура внизу экрана."""
    return ReplyKeyboardMarkup(
        keyboard=[[KeyboardButton(text=BTN_MENU), KeyboardButton(text=BTN_HELP)]],
        resize_keyboard=True,  # компактные кнопки
    )


def menu_kb() -> InlineKeyboardMarkup:
    """Главное инлайн-меню: по кнопке на каждую страницу из PAGES."""
    rows = [
        [InlineKeyboardButton(text=title, callback_data=f"page:{key}")]
        for key, (title, _) in PAGES.items()
    ]
    return InlineKeyboardMarkup(inline_keyboard=rows)


def back_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[[InlineKeyboardButton(text="⬅️ Назад", callback_data="menu")]]
    )


@router.message(CommandStart())
async def start(message: Message) -> None:
    await message.answer(
        f"Привет, <b>{message.from_user.full_name}</b>! 👋\nВыберите раздел:",
        reply_markup=main_reply_kb(),
    )
    await message.answer("Главное меню:", reply_markup=menu_kb())


@router.message(F.text == BTN_MENU)  # F.text == ... — фильтр по тексту сообщения
async def show_menu(message: Message) -> None:
    await message.answer("Главное меню:", reply_markup=menu_kb())


@router.message(F.text == BTN_HELP)
async def show_help(message: Message) -> None:
    await message.answer("Нажмите «📋 Меню» и выберите нужный раздел.")


@router.callback_query(F.data == "menu")
async def cb_menu(call: CallbackQuery) -> None:
    # edit_text заменяет сообщение, а не плодит новые — меню выглядит аккуратно.
    await call.message.edit_text("Главное меню:", reply_markup=menu_kb())
    await call.answer()  # обязательно: убирает «часики» на кнопке


@router.callback_query(F.data.startswith("page:"))
async def cb_page(call: CallbackQuery) -> None:
    key = call.data.split(":", 1)[1]
    if key not in PAGES:
        await call.answer("Раздел не найден", show_alert=True)
        return
    title, text = PAGES[key]
    await call.message.edit_text(f"<b>{title}</b>\n\n{text}", reply_markup=back_kb())
    await call.answer()


if __name__ == "__main__":
    run_bot(router)
