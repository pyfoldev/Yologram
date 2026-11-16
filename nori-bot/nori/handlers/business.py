from __future__ import annotations

import logging
import time

from aiogram import Bot, Router
from aiogram.exceptions import TelegramAPIError
from aiogram.types import BusinessConnection, BusinessMessagesDeleted, Message

from nori.db import Database
from nori.handlers import commands
from nori.utils import (KIND_TITLES, esc, extract_media, in_window, local_minutes, parse_hhmm, send_media, user_link)

router = Router(name="business")

OWNER_ACTIVE_SECS = 600     # если владелец сам писал в чат недавно — автоответчик молчит
TRIGGER_COOLDOWN = 60
SAVE_KINDS = {"photo", "video", "video_note", "voice"}

_self_deleted: set[tuple[int, int]] = set()      # сообщения, которые удалил сам бот (мут/команды) — не показывать как «удалённые»
_owner_last: dict[tuple[int, int], float] = {}
_trigger_last: dict[tuple[int, int, int], float] = {}
_warned: set[int] = set()


def _remember_self_deleted(key) -> None:
    if len(_self_deleted) > 5000:
        _self_deleted.clear()
    _self_deleted.add(key)


def _rights(bc: BusinessConnection) -> tuple[bool, bool, bool]:
    r = bc.rights
    can_reply = bool(bc.can_reply or (r and r.can_reply))
    can_del = bool(r and (r.can_delete_sent_messages or getattr(r, "can_delete_outgoing_messages", False)))
    can_del_all = bool(r and r.can_delete_all_messages)
    return can_reply, can_del, can_del_all


async def _conn(bot: Bot, db: Database, cid: str) -> dict | None:
    c = await db.get_connection(cid)
    if c:
        return c
    try:
        bc = await bot.get_business_connection(cid)
    except TelegramAPIError:
        return None
    cr, cd, cda = _rights(bc)
    await db.save_connection(bc.id, bc.user.id, bc.user_chat_id, cr, cd, cda, bc.is_enabled)
    return await db.get_connection(cid)


def _hm(ts: int, tz: str) -> str:
    t = time.gmtime(ts + int(tz) * 3600)
    return f"{t.tm_hour:02d}:{t.tm_min:02d}"


@router.business_connection()
async def on_connection(bc: BusinessConnection, bot: Bot, db: Database) -> None:
    cr, cd, cda = _rights(bc)
    await db.save_connection(bc.id, bc.user.id, bc.user_chat_id, cr, cd, cda, bc.is_enabled)
    if not bc.is_enabled:
        await bot.send_message(bc.user_chat_id, "🔌 Nori отключён от вашего аккаунта. Подключить снова: "
                                                "Настройки Telegram → Business → Чат-боты.")
        return
    flags = [("отвечать в чатах", cr), ("удалять свои сообщения (команды)", cd), ("удалять чужие (мут)", cda)]
    lines = "\n".join(f"{'✅' if ok else '❌'} {name}" for name, ok in flags)
    await bot.send_message(
        bc.user_chat_id,
        f"✅ <b>Nori подключён!</b>\n\n{lines}\n\n"
        "Теперь я присылаю сюда удалённые и изменённые сообщения, исчезающее медиа и работаю автоответчиком. "
        "Настройки — /start, команды — /help." +
        ("" if cda else "\n\n💡 Чтобы мут реально удалял сообщения, дайте боту право «Удалять любые сообщения» "
                        "(Настройки → Business → Чат-боты)."))


@router.business_message()
async def on_message(m: Message, bot: Bot, db: Database) -> None:
    conn = await _conn(bot, db, m.business_connection_id)
    if not conn or not conn["enabled"] or m.from_user is None:
        return
    owner, pm = conn["user_id"], conn["user_chat_id"]
    mine = m.from_user.id == owner
    peer = m.chat.full_name or str(m.chat.id)
    kind, fid = extract_media(m)
    text = m.text or m.caption or ""
    await db.cache_message(owner, m.chat.id, m.message_id, m.from_user.id, m.from_user.full_name, text, kind, fid)
    await db.count(owner, m.chat.id, peer, mine)

    if mine:
        _owner_last[(owner, m.chat.id)] = time.time()
        bot_info = await bot.me()
        await commands.handle(m, bot, db, conn, _self_deleted, bot_info.username)
        return
    if m.from_user.is_bot:
        return

    # --- мут ---
    if await db.is_muted(owner, m.chat.id):
        if conn["can_delete_all"]:
            _remember_self_deleted((m.chat.id, m.message_id))
            try:
                await bot.delete_business_messages(m.business_connection_id, [m.message_id])
            except TelegramAPIError as e:
                logging.warning("mute delete failed: %s", e)
        return

    # --- исчезающее / входящее медиа ---
    if kind in SAVE_KINDS and await db.flag(owner, "save_media"):
        who = f"{esc(m.from_user.full_name)}"
        try:
            await send_media(bot, pm, kind, fid, f"{KIND_TITLES[kind]} от <b>{who}</b>" + (f"\n{esc(text)}" if text else ""))
        except TelegramAPIError as e:
            logging.warning("media save failed: %s", e)

    # --- VIP ---
    if await db.is_vip(owner, m.chat.id):
        await bot.send_message(pm, f"⭐ {user_link(m.from_user.id, m.from_user.full_name)}: {esc(text) or KIND_TITLES.get(kind, 'сообщение')}")

    if not conn["can_reply"] or not text and kind is None:
        return
    # --- триггеры по ключевым словам ---
    low = text.lower()
    for t in await db.triggers(owner):
        if t["keyword"] in low:
            key = (owner, m.chat.id, t["id"])
            if time.time() - _trigger_last.get(key, 0) >= TRIGGER_COOLDOWN:
                _trigger_last[key] = time.time()
                await _send(bot, m, t["reply"], m.from_user.first_name)
                return
    await _autoreply(m, bot, db, owner)


async def _send(bot: Bot, m: Message, text: str, name: str) -> None:
    try:
        await bot.send_message(m.chat.id, text.replace("{name}", name), business_connection_id=m.business_connection_id,
                               parse_mode=None)
    except TelegramAPIError as e:
        logging.warning("business send failed: %s", e)


async def autoreply_active(db: Database, owner: int, now_min: int | None = None) -> bool:
    if not await db.flag(owner, "ar_on"):
        return False
    if await db.get(owner, "ar_mode") == "always":
        return True
    start, end = parse_hhmm(await db.get(owner, "ar_from")), parse_hhmm(await db.get(owner, "ar_to"))
    if start is None or end is None:
        return True
    if now_min is None:
        now_min = local_minutes(int(await db.get(owner, "tz")))
    return in_window(now_min, start, end)


async def _autoreply(m: Message, bot: Bot, db: Database, owner: int) -> None:
    if not await autoreply_active(db, owner):
        return
    if time.time() - _owner_last.get((owner, m.chat.id), 0) < OWNER_ACTIVE_SECS:
        return
    cooldown = int(await db.get(owner, "ar_cooldown")) * 60
    if time.time() - await db.last_autoreply(owner, m.chat.id) < cooldown:
        return
    await db.mark_autoreply(owner, m.chat.id)
    await _send(bot, m, await db.get(owner, "ar_text"), m.from_user.first_name)


@router.edited_business_message()
async def on_edited(m: Message, bot: Bot, db: Database) -> None:
    conn = await _conn(bot, db, m.business_connection_id)
    if not conn or not conn["enabled"] or m.from_user is None:
        return
    owner, pm = conn["user_id"], conn["user_chat_id"]
    old = await db.get_message(owner, m.chat.id, m.message_id)
    kind, fid = extract_media(m)
    new = m.text or m.caption or ""
    await db.cache_message(owner, m.chat.id, m.message_id, m.from_user.id, m.from_user.full_name, new, kind, fid,
                           old["ts"] if old else None)
    if m.from_user.id == owner or not old or old["text"] == new or not await db.flag(owner, "notify_edited"):
        return
    if await db.is_muted(owner, m.chat.id):
        return
    await bot.send_message(
        pm, f"✏️ <b>{esc(m.from_user.full_name)}</b> изменил(а) сообщение\n"
            f"<b>Было:</b>\n<blockquote>{esc(old['text'] or '—')}</blockquote>\n"
            f"<b>Стало:</b>\n<blockquote>{esc(new or '—')}</blockquote>")


@router.deleted_business_messages()
async def on_deleted(ev: BusinessMessagesDeleted, bot: Bot, db: Database) -> None:
    conn = await _conn(bot, db, ev.business_connection_id)
    if not conn or not conn["enabled"]:
        return
    owner, pm = conn["user_id"], conn["user_chat_id"]
    notify = await db.flag(owner, "notify_deleted")
    tz = await db.get(owner, "tz")
    for mid in ev.message_ids:
        if (ev.chat.id, mid) in _self_deleted:
            _self_deleted.discard((ev.chat.id, mid))
            continue
        row = await db.get_message(owner, ev.chat.id, mid)
        if not row or row["uid"] == owner:
            continue
        await db.log_deleted(owner, ev.chat.id, row["name"], row["text"], row["kind"])
        if not notify or await db.is_muted(owner, ev.chat.id):
            continue
        head = f"🗑 <b>{esc(row['name'])}</b> удалил(а) сообщение · <i>{_hm(row['ts'], tz)}</i>"
        try:
            if row["kind"] and row["file_id"]:
                cap = f"{head}\n{KIND_TITLES.get(row['kind'], '')}" + (f"\n<blockquote>{esc(row['text'])}</blockquote>" if row["text"] else "")
                await send_media(bot, pm, row["kind"], row["file_id"], cap)
            else:
                await bot.send_message(pm, f"{head}\n<blockquote>{esc(row['text'] or '—')}</blockquote>")
        except TelegramAPIError as e:
            logging.warning("deleted notify failed: %s", e)
