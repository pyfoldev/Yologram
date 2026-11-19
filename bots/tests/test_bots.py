"""Тесты всех десяти ботов. Запуск:  cd bots && pytest"""
import importlib
import time

import pytest
from aiogram import Router

from .conftest import ADMIN_ID

MODULES = [
    "bot01_menu", "bot02_quiz", "bot03_todo", "bot04_reminder", "bot05_form",
    "bot06_support", "bot07_moderator", "bot08_info", "bot09_shop", "bot10_ai",
]


def load(name):
    return importlib.import_module(f"bots.{name}")


@pytest.mark.parametrize("name", MODULES)
def test_every_bot_has_router_and_entrypoint(name):
    mod = load(name)
    assert isinstance(mod.router, Router)
    assert mod.__doc__ and "Запуск" in mod.__doc__  # у каждого бота есть инструкция


# ---------- 01 меню ----------
async def test_menu(harness):
    m = load("bot01_menu")
    h = harness(m.router)
    await h.say("/start")
    assert len(h.texts()) == 2
    await h.press("page:prices")
    assert "Цены" in h.calls_named("EditMessageText")[-1].text
    await h.press("page:nope")  # неизвестный раздел не роняет бота


# ---------- 02 викторина ----------
async def test_quiz_full_game(harness):
    m = load("bot02_quiz")
    h = harness(m.router)
    await h.say("/quiz")
    for i, q in enumerate(m.QUESTIONS):
        await h.press(f"ans:{i}:{q.correct}")
    assert f"{len(m.QUESTIONS)}/{len(m.QUESTIONS)}" in h.calls_named("EditMessageText")[-1].text
    await h.press("ans:0:0")  # игра закончилась — старая кнопка безвредна
    assert 1 not in m.games


# ---------- 03 todo ----------
async def test_todo_isolated_per_user(harness):
    m = load("bot03_todo")
    h = harness(m.router)
    await h.say("/add купить хлеб", uid=1)
    await h.say("/add чужая задача", uid=2)
    await h.say("/list", uid=1)
    assert "купить хлеб" in h.last() and "чужая" not in h.last()
    task = m.db.one("SELECT id FROM tasks WHERE user_id = 1")["id"]
    await h.press(f"toggle:{task}", uid=2)  # чужой пользователь не может менять задачу
    assert m.db.one("SELECT done FROM tasks WHERE id = ?", (task,))["done"] == 0
    await h.press(f"toggle:{task}", uid=1)
    assert m.db.one("SELECT done FROM tasks WHERE id = ?", (task,))["done"] == 1


# ---------- 04 напоминания ----------
def test_parse_delay():
    m = load("bot04_reminder")
    assert m.parse_delay("30m") == 1800 and m.parse_delay("2ч") == 7200
    assert m.parse_delay("1d") == 86400 and m.parse_delay("abc") is None


async def test_reminder_fires(harness):
    m = load("bot04_reminder")
    h = harness(m.router)
    await h.say("/remind 1s выпить воды", uid=5)
    assert "Хорошо" in h.last()
    await __import__("asyncio").sleep(1.3)
    assert "выпить воды" in h.last()
    assert m.db.query("SELECT * FROM reminders WHERE chat_id = 5") == []


# ---------- 05 анкета (FSM) ----------
async def test_form_flow(harness):
    m = load("bot05_form")
    h = harness(m.router)
    await h.say("/start")
    await h.say("Иван")
    await h.say("abc")  # неверный возраст — остаёмся на шаге
    assert "числом" in h.last()
    await h.say("30")
    await h.say("12")  # неверный телефон
    assert "номер" in h.last()
    await h.say("+79991234567")
    await h.say("✅ Отправить")
    admin_msgs = [c for c in h.sent() if c.chat_id == ADMIN_ID]
    assert admin_msgs and "Иван" in admin_msgs[0].text and "30" in admin_msgs[0].text


# ---------- 06 поддержка ----------
async def test_support_relay(harness):
    m = load("bot06_support")
    h = harness(m.router)
    await h.say("Не работает оплата", uid=7)
    copies = h.calls_named("CopyMessage")
    assert copies and copies[0].chat_id == ADMIN_ID
    # админ отвечает Reply'ем на скопированное сообщение
    row = m.db.query("SELECT * FROM relay")[0]
    reply_to = h._message("x", ADMIN_ID, message_id=row["admin_msg"])
    await h.say("Починим!", uid=ADMIN_ID, reply_to=reply_to)
    assert h.calls_named("CopyMessage")[-1].chat_id == 7  # ответ ушёл пользователю


# ---------- 07 модератор ----------
def test_violation_detector():
    m = load("bot07_moderator")
    assert m.is_violation("Заходи https://spam.ru")
    assert m.is_violation("лучшее КАЗИНО тут")
    assert m.is_violation("Всем привет!") is None


async def test_moderator_warns_then_bans(harness):
    m = load("bot07_moderator")
    h = harness(m.router)
    for _ in range(3):
        await h.say("купи на t.me/xxx", uid=50, chat_id=-100, chat_type="supergroup")
    assert len(h.calls_named("DeleteMessage")) == 3
    assert len(h.calls_named("BanChatMember")) == 1
    # админа не трогаем
    h.session.admins.add(60)
    await h.say("ссылка https://ok.ru", uid=60, chat_id=-100, chat_type="supergroup")
    assert len(h.calls_named("DeleteMessage")) == 3
    # личные сообщения игнорируются
    await h.say("https://x.ru", uid=70)
    assert len(h.calls_named("DeleteMessage")) == 3


# ---------- 08 информер ----------
async def test_info_rate(harness, monkeypatch):
    m = load("bot08_info")

    async def fake_fetch(url, **params):
        return {"result": "success", "rates": {"USD": 1.0, "RUB": 90.0, "EUR": 0.9}}

    monkeypatch.setattr(m, "fetch_json", fake_fetch)
    h = harness(m.router)
    await h.say("/rate 100 USD RUB")
    assert "9,000.00" in h.last()
    await h.say("/rate 1 USD XXX")
    assert "не найдена" in h.last()


async def test_info_api_down(harness, monkeypatch):
    m = load("bot08_info")

    async def boom(url, **params):
        raise m.ApiError("Сервис временно недоступен")

    monkeypatch.setattr(m, "fetch_json", boom)
    h = harness(m.router)
    await h.say("/weather Москва")
    assert "недоступен" in h.last()


# ---------- 09 магазин ----------
async def test_shop_cart_and_checkout(harness):
    m = load("bot09_shop")
    h = harness(m.router)
    await h.press("add:1", uid=3)
    await h.press("add:1", uid=3)
    await h.press("add:2", uid=3)
    assert "Итого: 1200" in m.cart_text(3)  # 450*2 + 300
    await h.press("checkout", uid=3)
    admin_msgs = [c for c in h.sent() if c.chat_id == ADMIN_ID]
    assert admin_msgs and "Новый заказ" in admin_msgs[0].text
    assert m.cart_items(3) == []
    await h.press("checkout", uid=3)  # пустая корзина — заказ не создаётся
    assert len([c for c in h.sent() if c.chat_id == ADMIN_ID]) == 1


# ---------- 10 ИИ ----------
def test_split_text():
    m = load("bot10_ai")
    parts = m.split_text("а\n" * 5000, 100)
    assert all(len(p) <= 100 for p in parts) and "".join(parts).replace("\n", "") == "а" * 5000
    assert m.split_text("") == []


async def test_ai_keeps_history(harness, monkeypatch):
    m = load("bot10_ai")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test")
    seen = []

    async def fake_ask(messages):
        seen.append(list(messages))
        return f"ответ {len(messages)}"

    monkeypatch.setattr(m, "ask_claude", fake_ask)
    h = harness(m.router)
    await h.say("привет", uid=4)
    await h.say("как дела?", uid=4)
    assert [x["role"] for x in seen[1]] == ["user", "assistant", "user"]
    await h.say("/reset", uid=4)
    assert 4 not in m.history
