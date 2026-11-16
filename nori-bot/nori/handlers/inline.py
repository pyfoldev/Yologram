"""Инлайн-режим: @бот в любом чате → игра на двоих."""
from aiogram import Router
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup, InlineQuery, InlineQueryResultArticle, InputTextMessageContent

router = Router(name="inline")


def _join(data: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[InlineKeyboardButton(text="⚔️ Вступить", callback_data=data)]])


@router.inline_query()
async def on_inline(q: InlineQuery) -> None:
    results = [
        InlineQueryResultArticle(
            id="ttt", title="❌⭕ Крестики-нолики", description="Игра на двоих с кнопками",
            input_message_content=InputTextMessageContent(message_text="❌⭕ <b>Крестики-нолики</b>\nКто сыграет? Нажмите «Вступить»."),
            reply_markup=_join("g:ptt:join")),
        InlineQueryResultArticle(
            id="rps", title="✊✌️🖐 Камень-ножницы-бумага", description="Дуэль на двоих, выбор втайне",
            input_message_content=InputTextMessageContent(message_text="✊✌️🖐 <b>Камень-ножницы-бумага</b>\nКто сыграет? Нажмите «Вступить»."),
            reply_markup=_join("g:prps:join")),
    ]
    await q.answer(results, cache_time=5, is_personal=False)
