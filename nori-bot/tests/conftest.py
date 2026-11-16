import datetime

import pytest
from aiogram import Bot, Dispatcher
from aiogram.client.session.base import BaseSession
from aiogram.methods import SendMessage, GetMe
from aiogram.types import Chat, Message, User

from nori.db import Database
from nori.handlers import business, games, inline, panel


class FakeSession(BaseSession):
    def __init__(self):
        super().__init__()
        self.calls = []

    async def close(self): ...
    async def stream_content(self, *a, **k): ...

    async def make_request(self, bot, method, timeout=None):
        self.calls.append(method)
        name = type(method).__name__
        if name == "GetMe":
            return User(id=999, is_bot=True, first_name="Nori", username="nori_bot")
        if name.startswith("Send") or name.startswith("Edit"):
            return Message(message_id=500 + len(self.calls), date=datetime.datetime.now(),
                           chat=Chat(id=getattr(method, "chat_id", 1) if isinstance(getattr(method, "chat_id", 1), int) else 1, type="private"))
        return True

    def sent(self, cls_name):
        return [c for c in self.calls if type(c).__name__ == cls_name]


@pytest.fixture
async def env():
    db = Database(":memory:")
    await db.init()
    session = FakeSession()
    bot = Bot("1:TEST", session=session)
    dp = Dispatcher(db=db)
    for r in (business.router, games.router, inline.router, panel.router):
        r._parent_router = None   # роутеры модульные: между тестами отцепляем
    games.STATE.clear(); panel.AWAIT.clear()
    dp.include_routers(business.router, games.router, inline.router, panel.router)
    business._self_deleted.clear(); business._owner_last.clear(); business._trigger_last.clear()
    yield db, bot, dp, session
    await db.close()
