"""Телеграм-бот: 200 шаблонов стикеров и премиум-эмодзи со своим текстом."""
import asyncio
import logging
import os

from aiogram import Bot, Dispatcher, F, Router
from aiogram.exceptions import TelegramBadRequest
from aiogram.filters import Command, CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import (BufferedInputFile, CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup,
                           InputMediaPhoto, InputSticker, Message)
from aiogram.utils.keyboard import InlineKeyboardBuilder

import render
from templates import TEMPLATES

PER_PAGE = 20
PAGES = (len(TEMPLATES) + PER_PAGE - 1) // PER_PAGE
MAX_TEXT = 40
router = Router()
_cache: dict = {}


class S(StatesGroup):
    text = State()


def gallery_png(page: int) -> bytes:
    if page not in _cache:
        _cache[page] = render.gallery(page)
    return _cache[page]


def kb_type() -> InlineKeyboardMarkup:
    b = InlineKeyboardBuilder()
    b.button(text="🖼 Стикер (512×512)", callback_data="kind:sticker")
    b.button(text="⭐ Премиум-эмодзи (100×100)", callback_data="kind:emoji")
    b.adjust(1)
    return b.as_markup()


def kb_gallery(page: int) -> InlineKeyboardMarkup:
    b = InlineKeyboardBuilder()
    start = page * PER_PAGE
    for tid in range(start + 1, min(start + PER_PAGE, len(TEMPLATES)) + 1):
        b.button(text=str(tid), callback_data=f"t:{tid}")
    b.adjust(5)
    nav = InlineKeyboardBuilder()
    nav.button(text="◀️", callback_data=f"p:{(page - 1) % PAGES}")
    nav.button(text=f"{page + 1}/{PAGES}", callback_data="noop")
    nav.button(text="▶️", callback_data=f"p:{(page + 1) % PAGES}")
    b.attach(nav.adjust(3))
    return b.as_markup()


def kb_result() -> InlineKeyboardMarkup:
    b = InlineKeyboardBuilder()
    b.button(text="➕ Добавить в мой набор", callback_data="add")
    b.button(text="✏️ Другой текст", callback_data="retext")
    b.button(text="🎨 Другой шаблон", callback_data="gallery")
    b.adjust(1)
    return b.as_markup()


async def show_gallery(target: Message, page: int, edit: bool = False):
    photo = BufferedInputFile(gallery_png(page), "gallery.png")
    cap = "Выбери номер шаблона 👇 (всего 200)"
    if edit:
        await target.edit_media(InputMediaPhoto(media=photo, caption=cap), reply_markup=kb_gallery(page))
    else:
        await target.answer_photo(photo, caption=cap, reply_markup=kb_gallery(page))


@router.message(CommandStart())
async def start(m: Message, state: FSMContext):
    await state.clear()
    await m.answer("Привет! Я делаю стикеры и премиум-эмодзи со своим текстом.\n"
                   "200 шаблонов: кепки, часы, сумки, билеты, щиты и другое.\n\nЧто делаем?",
                   reply_markup=kb_type())


@router.message(Command("help"))
async def help_(m: Message):
    await m.answer("/start — начать\nВыбери тип → шаблон → напиши текст → добавь в свой набор.\n"
                   "Эмодзи-набор могут использовать в сообщениях пользователи с Telegram Premium.")


@router.callback_query(F.data == "noop")
async def noop(c: CallbackQuery):
    await c.answer()


@router.callback_query(F.data.startswith("kind:"))
async def pick_kind(c: CallbackQuery, state: FSMContext):
    await state.update_data(kind=c.data.split(":")[1])
    await c.answer()
    await show_gallery(c.message, 0)


@router.callback_query(F.data == "gallery")
async def again(c: CallbackQuery):
    await c.answer()
    await show_gallery(c.message, 0)


@router.callback_query(F.data.startswith("p:"))
async def page(c: CallbackQuery):
    await c.answer()
    await show_gallery(c.message, int(c.data.split(":")[1]), edit=True)


@router.callback_query(F.data.startswith("t:"))
async def pick_template(c: CallbackQuery, state: FSMContext):
    tid = int(c.data.split(":")[1])
    data = await state.get_data()
    if "kind" not in data:
        await c.answer()
        return await c.message.answer("Сначала выбери тип:", reply_markup=kb_type())
    await state.update_data(tid=tid)
    await state.set_state(S.text)
    await c.answer()
    await c.message.answer(f"Шаблон №{tid}: {TEMPLATES[tid - 1]['name']}\n"
                           f"Напиши текст (до {MAX_TEXT} символов):")


@router.callback_query(F.data == "retext")
async def retext(c: CallbackQuery, state: FSMContext):
    await state.set_state(S.text)
    await c.answer()
    await c.message.answer("Напиши новый текст:")


@router.message(S.text, F.text)
async def got_text(m: Message, state: FSMContext):
    text = m.text.strip()[:MAX_TEXT]
    data = await state.get_data()
    size = 100 if data["kind"] == "emoji" else 512
    png = render.render(data["tid"], text, size)
    await state.update_data(png=png)
    await m.answer_sticker(BufferedInputFile(render.to_webp(png), "s.webp"))
    await m.answer("Готово! Добавить в твой набор?", reply_markup=kb_result())


def pack_name(user_id: int, kind: str, bot_username: str) -> str:
    return f"{kind[0]}{user_id}_by_{bot_username}"


@router.callback_query(F.data == "add")
async def add(c: CallbackQuery, state: FSMContext, bot: Bot):
    data = await state.get_data()
    if "png" not in data:
        return await c.answer("Сначала создай стикер", show_alert=True)
    kind = data["kind"]
    me = await bot.get_me()
    name = pack_name(c.from_user.id, kind, me.username)
    item = InputSticker(sticker=BufferedInputFile(data["png"], "s.png"), format="static", emoji_list=["✨"])
    try:
        try:
            await bot.get_sticker_set(name)
            await bot.add_sticker_to_set(user_id=c.from_user.id, name=name, sticker=item)
        except TelegramBadRequest as e:
            if "STICKERSET_INVALID" not in str(e) and "not found" not in str(e).lower():
                raise
            await bot.create_new_sticker_set(
                user_id=c.from_user.id, name=name,
                title="Мои эмодзи" if kind == "emoji" else "Мои стикеры",
                stickers=[item], sticker_type="custom_emoji" if kind == "emoji" else "regular")
    except TelegramBadRequest as e:
        logging.exception("add failed")
        return await c.answer(f"Не вышло: {e.message[:150]}", show_alert=True)
    await c.answer("Добавлено ✅")
    link = f"https://t.me/{'addemoji' if kind == 'emoji' else 'addstickers'}/{name}"
    await c.message.answer(f"Твой набор: {link}")


async def main():
    logging.basicConfig(level=logging.INFO)
    token = os.environ.get("BOT_TOKEN")
    if not token:
        raise SystemExit("Задай BOT_TOKEN (токен от @BotFather)")
    bot = Bot(token)
    dp = Dispatcher()
    dp.include_router(router)
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
