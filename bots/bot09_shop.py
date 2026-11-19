"""БОТ №9 — МАГАЗИН: каталог, корзина, оформление заказа.

Чему учит:
  • каталог из словаря и карточки товаров с кнопками;
  • корзина пользователя в SQLite;
  • оформление заказа: уведомление админам.
(Оплату подключают через Telegram Payments — см. bot.send_invoice в документации.)

Запуск:  python -m bots.bot09_shop     (ADMIN_IDS — кому слать заказы)
Как расширить: вынесите CATALOG в БД/JSON и добавьте админ-команды управления.
"""
from aiogram import Bot, F, Router
from aiogram.filters import Command, CommandStart
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

from .core import Database, admin_ids, run_bot

router = Router()

# id товара -> (название, цена в ₽). Правьте под свой ассортимент.
CATALOG: dict[int, tuple[str, int]] = {
    1: ("☕️ Кофе 250 г", 450),
    2: ("🍵 Чай зелёный", 300),
    3: ("🍪 Печенье", 150),
}

SCHEMA = """
CREATE TABLE IF NOT EXISTS cart (
    user_id INTEGER NOT NULL,
    item_id INTEGER NOT NULL,
    qty     INTEGER NOT NULL,
    PRIMARY KEY (user_id, item_id)
);
"""
db = Database("shop", SCHEMA)


def catalog_kb() -> InlineKeyboardMarkup:
    rows = [
        [InlineKeyboardButton(text=f"{name} — {price} ₽", callback_data=f"add:{iid}")]
        for iid, (name, price) in CATALOG.items()
    ]
    rows.append([InlineKeyboardButton(text="🛒 Корзина", callback_data="cart")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def cart_items(user_id: int) -> list[tuple[int, str, int, int]]:
    """[(id, название, цена, количество)] — товары в корзине."""
    rows = db.query("SELECT item_id, qty FROM cart WHERE user_id = ? ORDER BY item_id", (user_id,))
    return [(r["item_id"], *CATALOG[r["item_id"]], r["qty"]) for r in rows if r["item_id"] in CATALOG]


def cart_text(user_id: int) -> str:
    items = cart_items(user_id)
    if not items:
        return "🛒 Корзина пуста."
    lines = [f"{name} × {qty} = {price * qty} ₽" for _, name, price, qty in items]
    total = sum(price * qty for _, _, price, qty in items)
    return "🛒 <b>Корзина</b>\n" + "\n".join(lines) + f"\n\n<b>Итого: {total} ₽</b>"


def cart_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="✅ Оформить заказ", callback_data="checkout")],
            [InlineKeyboardButton(text="🗑 Очистить", callback_data="clear")],
            [InlineKeyboardButton(text="⬅️ В каталог", callback_data="catalog")],
        ]
    )


@router.message(CommandStart())
@router.message(Command("catalog"))
async def show_catalog(message: Message) -> None:
    await message.answer("🛍 <b>Каталог</b>. Нажмите на товар, чтобы добавить в корзину:", reply_markup=catalog_kb())


@router.callback_query(F.data == "catalog")
async def cb_catalog(call: CallbackQuery) -> None:
    await call.message.edit_text("🛍 <b>Каталог</b>:", reply_markup=catalog_kb())
    await call.answer()


@router.callback_query(F.data.startswith("add:"))
async def cb_add(call: CallbackQuery) -> None:
    item_id = int(call.data.split(":")[1])
    if item_id not in CATALOG:
        await call.answer("Товар недоступен", show_alert=True)
        return
    # UPSERT: если товар уже в корзине — увеличиваем количество.
    db.execute(
        "INSERT INTO cart VALUES (?, ?, 1) ON CONFLICT(user_id, item_id) DO UPDATE SET qty = qty + 1",
        (call.from_user.id, item_id),
    )
    await call.answer(f"Добавлено: {CATALOG[item_id][0]}")


@router.callback_query(F.data == "cart")
async def cb_cart(call: CallbackQuery) -> None:
    await call.message.edit_text(cart_text(call.from_user.id), reply_markup=cart_kb())
    await call.answer()


@router.callback_query(F.data == "clear")
async def cb_clear(call: CallbackQuery) -> None:
    db.execute("DELETE FROM cart WHERE user_id = ?", (call.from_user.id,))
    await call.message.edit_text("🛒 Корзина очищена.", reply_markup=cart_kb())
    await call.answer()


@router.callback_query(F.data == "checkout")
async def cb_checkout(call: CallbackQuery, bot: Bot) -> None:
    if not cart_items(call.from_user.id):
        await call.answer("Корзина пуста", show_alert=True)
        return
    order = cart_text(call.from_user.id).replace("🛒 <b>Корзина</b>", "📦 <b>Новый заказ</b>")
    user = call.from_user
    for admin in admin_ids():
        await bot.send_message(admin, f"{order}\n\nОт: {user.full_name} (id {user.id})")
    db.execute("DELETE FROM cart WHERE user_id = ?", (user.id,))
    await call.message.edit_text("🎉 Заказ оформлен! Мы свяжемся с вами.")
    await call.answer()


if __name__ == "__main__":
    run_bot(router)
