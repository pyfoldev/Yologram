import datetime
import time

from aiogram.types import Update

from nori.handlers import business

OWNER, PEER, CID = 111, 222, "conn1"
NOW = int(time.time())


def upd(**kw):
    return Update.model_validate({"update_id": 1, **kw})


def msg(mid, uid, text=None, **extra):
    chat = {"id": PEER, "type": "private", "first_name": "Вася"}
    return {"message_id": mid, "date": NOW, "chat": chat, "business_connection_id": CID,
            "from": {"id": uid, "is_bot": False, "first_name": "Вася" if uid == PEER else "Я"},
            **({"text": text} if text is not None else {}), **extra}


async def connect(dp, bot, can_del_all=True):
    await dp.feed_update(bot, upd(business_connection={
        "id": CID, "user": {"id": OWNER, "is_bot": False, "first_name": "Я"}, "user_chat_id": OWNER, "date": NOW,
        "is_enabled": True, "rights": {"can_reply": True, "can_delete_sent_messages": True, "can_delete_all_messages": can_del_all}}))


def texts(session, chat=None):
    return [c.text for c in session.sent("SendMessage") if chat is None or c.chat_id == chat]


async def test_connect_and_deleted_and_edited(env):
    db, bot, dp, s = env
    await connect(dp, bot)
    assert any("подключён" in t for t in texts(s, OWNER))
    await dp.feed_update(bot, upd(business_message=msg(10, PEER, "секретное сообщение")))
    await dp.feed_update(bot, upd(edited_business_message=msg(10, PEER, "новый текст")))
    assert any("изменил" in t and "секретное" in t and "новый текст" in t for t in texts(s, OWNER))
    await dp.feed_update(bot, upd(deleted_business_messages={"business_connection_id": CID, "chat": {"id": PEER, "type": "private", "first_name": "Вася"}, "message_ids": [10]}))
    assert any("удалил" in t and "новый текст" in t for t in texts(s, OWNER))


async def test_media_saved_and_deleted_media(env):
    db, bot, dp, s = env
    await connect(dp, bot)
    await dp.feed_update(bot, upd(business_message=msg(11, PEER, photo=[{"file_id": "F1", "file_unique_id": "u", "width": 1, "height": 1}], caption="смотри")))
    assert [c.photo for c in s.sent("SendPhoto")] == ["F1"] and s.sent("SendPhoto")[0].chat_id == OWNER


async def test_mute_command_and_delete(env):
    db, bot, dp, s = env
    await connect(dp, bot)
    await dp.feed_update(bot, upd(business_message=msg(20, OWNER, ".mute 1ч")))
    assert await db.is_muted(OWNER, PEER)
    assert any(type(c).__name__ == "DeleteBusinessMessages" and c.message_ids == [20] for c in s.calls)
    s.calls.clear()
    await dp.feed_update(bot, upd(business_message=msg(21, PEER, "ответь мне")))
    dels = s.sent("DeleteBusinessMessages")
    assert dels and dels[0].message_ids == [21] and not texts(s, PEER)
    # самостоятельное удаление ботом не показывается как «удалённое»
    s.calls.clear()
    await dp.feed_update(bot, upd(deleted_business_messages={"business_connection_id": CID, "chat": {"id": PEER, "type": "private"}, "message_ids": [21]}))
    assert not texts(s, OWNER)
    await dp.feed_update(bot, upd(business_message=msg(22, OWNER, ".unmute")))
    assert not await db.is_muted(OWNER, PEER)


async def test_autoreply_and_cooldown_and_triggers(env):
    db, bot, dp, s = env
    await connect(dp, bot)
    await db.set(OWNER, "ar_on", "1")
    await db.add_trigger(OWNER, "цена", "Цена 100₽, {name}")
    await dp.feed_update(bot, upd(business_message=msg(30, PEER, "привет")))
    sent = [c for c in s.sent("SendMessage") if c.chat_id == PEER]
    assert len(sent) == 1 and "Вася" in sent[0].text and sent[0].business_connection_id == CID
    await dp.feed_update(bot, upd(business_message=msg(31, PEER, "ещё раз")))          # кулдаун
    assert len([c for c in s.sent("SendMessage") if c.chat_id == PEER]) == 1
    await dp.feed_update(bot, upd(business_message=msg(32, PEER, "а какая ЦЕНА?")))     # триггер
    assert "Цена 100₽, Вася" in texts(s, PEER)[-1]


async def test_autoreply_silent_when_owner_active_or_off(env):
    db, bot, dp, s = env
    await connect(dp, bot)
    await dp.feed_update(bot, upd(business_message=msg(40, PEER, "привет")))
    assert not texts(s, PEER)                                           # выключен
    await db.set(OWNER, "ar_on", "1")
    await dp.feed_update(bot, upd(business_message=msg(41, OWNER, "щас отвечу")))
    await dp.feed_update(bot, upd(business_message=msg(42, PEER, "ок")))
    assert not texts(s, PEER)                                           # владелец сам в чате


async def test_save_command_on_reply(env):
    db, bot, dp, s = env
    await connect(dp, bot)
    reply = msg(50, PEER, video_note={"file_id": "VN", "file_unique_id": "u", "length": 1, "duration": 1})
    await db.set(OWNER, "save_media", "0")
    await dp.feed_update(bot, upd(business_message=msg(51, OWNER, ".save", reply_to_message=reply)))
    assert [c.video_note for c in s.sent("SendVideoNote")] == ["VN"]


async def test_calc_edits_message(env):
    db, bot, dp, s = env
    await connect(dp, bot)
    await dp.feed_update(bot, upd(business_message=msg(60, OWNER, ".calc 12*12")))
    e = s.sent("EditMessageText")
    assert e and e[0].text == "12*12 = 144" and e[0].business_connection_id == CID


async def test_games_and_panel(env):
    db, bot, dp, s = env
    me = {"id": 5, "is_bot": False, "first_name": "Игрок"}
    pm = {"message_id": 1, "date": NOW, "chat": {"id": 5, "type": "private"}, "from": me}
    await dp.feed_update(bot, upd(message={**pm, "text": "/games", "entities": [{"type": "bot_command", "offset": 0, "length": 6}]}))
    assert "Мини-игры" in s.sent("SendMessage")[-1].text
    for game in ("ttt", "rps", "bj", "mines", "guess", "quiz", "coin", "slots"):
        await dp.feed_update(bot, upd(callback_query={"id": "c", "from": me, "chat_instance": "x", "data": f"g:new:{game}", "message": pm}))
    s.calls.clear()
    # крестики-нолики: ход игрока → ответ бота
    await dp.feed_update(bot, upd(callback_query={"id": "c", "from": me, "chat_instance": "x", "data": "g:new:ttt", "message": pm}))
    await dp.feed_update(bot, upd(callback_query={"id": "c", "from": me, "chat_instance": "x", "data": "g:ttt:4", "message": pm}))
    last = s.sent("EditMessageText")[-1]
    flat = [b.text for row in last.reply_markup.inline_keyboard for b in row]
    assert flat.count("❌") == 1 and flat.count("⭕") == 1
    # блэкджек: ставка списывается
    before = await db.balance(5)
    await dp.feed_update(bot, upd(callback_query={"id": "c", "from": me, "chat_instance": "x", "data": "g:bjs:10", "message": pm}))
    assert await db.balance(5) <= before - 10 + 25
    # /start показывает меню настроек
    await dp.feed_update(bot, upd(message={**pm, "text": "/start", "entities": [{"type": "bot_command", "offset": 0, "length": 6}]}))
    assert "Nori" in s.sent("SendMessage")[-1].text
    await dp.feed_update(bot, upd(callback_query={"id": "c", "from": me, "chat_instance": "x", "data": "p:t:notify_deleted", "message": pm}))
    assert await db.get(5, "notify_deleted") == "0"


async def test_pvp_ttt_inline(env):
    db, bot, dp, s = env
    a = {"id": 1, "is_bot": False, "first_name": "А"}
    b = {"id": 2, "is_bot": False, "first_name": "Б"}
    def cb(user, data):
        return upd(callback_query={"id": "c", "from": user, "chat_instance": "x", "data": data, "inline_message_id": "IM1"})
    await dp.feed_update(bot, cb(a, "g:ptt:join"))
    await dp.feed_update(bot, cb(b, "g:ptt:join"))
    await dp.feed_update(bot, cb(b, "g:ptt:0"))      # ходит X (А) — Б не может
    await dp.feed_update(bot, cb(a, "g:ptt:0"))
    await dp.feed_update(bot, cb(b, "g:ptt:3"))
    await dp.feed_update(bot, cb(a, "g:ptt:1"))
    await dp.feed_update(bot, cb(b, "g:ptt:4"))
    await dp.feed_update(bot, cb(a, "g:ptt:2"))      # А побеждает
    last = s.sent("EditMessageText")[-1]
    assert "Победил А" in last.text
