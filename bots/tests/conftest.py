"""Тестовая инфраструктура: «поддельный» Telegram без сети.

FakeSession подменяет HTTP-сессию aiogram: все вызовы API (sendMessage, ...)
записываются в список bot.calls, а боту возвращаются правдоподобные ответы.
Благодаря этому тестируются настоящие хендлеры — ровно как в боевом режиме.
"""
import os
import tempfile
from datetime import datetime

# ВАЖНО: до импорта ботов — их базы создаются на уровне модуля.
os.environ["BOTS_DATA_DIR"] = tempfile.mkdtemp(prefix="bots-test-")
os.environ["ADMIN_IDS"] = "999"

import pytest
from aiogram import Bot, Dispatcher
from aiogram.client.session.base import BaseSession
from aiogram.methods import CopyMessage, SendMessage
from aiogram.types import CallbackQuery, Chat, Message, MessageId, Update, User

ADMIN_ID = 999


class FakeSession(BaseSession):
    def __init__(self):
        super().__init__()
        self.calls: list = []
        self.counter = 1000
        self.admins: set[int] = set()  # кто считается админом чата (для бота-модератора)

    async def make_request(self, bot, method, timeout=None):
        self.calls.append(method)
        self.counter += 1
        name = type(method).__name__
        if name == "SendMessage":
            return Message(message_id=self.counter, date=datetime.now(),
                           chat=Chat(id=method.chat_id, type="private"), text=method.text)
        if name == "CopyMessage":
            return MessageId(message_id=self.counter)
        if name == "GetChatMember":
            from aiogram.types import ChatMemberMember, ChatMemberOwner
            user = User(id=method.user_id, is_bot=False, first_name="u")
            if method.user_id in self.admins:
                return ChatMemberOwner(user=user, is_anonymous=False)  # status 'creator' = админ
            return ChatMemberMember(user=user)
        return True

    async def close(self): ...
    async def stream_content(self, *a, **kw): yield b""


class Harness:
    """Обёртка: отправляет «входящие» события боту и позволяет читать его ответы."""

    def __init__(self, *routers):
        for r in routers:  # роутер можно подключить к диспетчеру лишь один раз — сбрасываем для тестов
            r._parent_router = None
        self.session = FakeSession()
        self.bot = Bot("123456:TEST", session=self.session)
        self.dp = Dispatcher()
        self.dp.include_routers(*routers)
        self.update_id = 0

    # --- вспомогательное ---
    def _user(self, uid): return User(id=uid, is_bot=False, first_name="Тест", username=f"user{uid}")

    def _message(self, text, uid, chat_id=None, chat_type="private", reply_to=None, message_id=None):
        self.update_id += 1
        return Message(message_id=message_id or self.update_id, date=datetime.now(), text=text,
                       chat=Chat(id=chat_id or uid, type=chat_type),
                       from_user=self._user(uid), reply_to_message=reply_to)

    async def say(self, text, uid=1, **kw):
        """Пользователь uid присылает текст."""
        msg = self._message(text, uid, **kw)
        await self.dp.feed_update(self.bot, Update(update_id=self.update_id, message=msg))
        return msg

    async def press(self, data, uid=1, message_id=1):
        """Пользователь uid нажимает инлайн-кнопку с callback_data=data."""
        self.update_id += 1
        cb = CallbackQuery(
            id=str(self.update_id), from_user=self._user(uid), chat_instance="x", data=data,
            message=Message(message_id=message_id, date=datetime.now(),
                            chat=Chat(id=uid, type="private"), from_user=self._user(1), text="old"))
        await self.dp.feed_update(self.bot, Update(update_id=self.update_id, callback_query=cb))

    # --- что бот сделал ---
    def sent(self, cls=SendMessage) -> list:
        return [c for c in self.session.calls if isinstance(c, cls)]

    def texts(self) -> list[str]:
        return [c.text for c in self.sent()]

    def last(self) -> str:
        return self.texts()[-1]

    def calls_named(self, name: str) -> list:
        return [c for c in self.session.calls if type(c).__name__ == name]


@pytest.fixture
def harness():
    """Фабрика: harness(router) -> Harness. Пример: h = harness(bot01_menu.router)."""
    return Harness
