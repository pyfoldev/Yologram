"""Команды владельца: он пишет их в любом бизнес-чате, начиная с точки (".mute 1ч")."""
from __future__ import annotations

import asyncio
import time

from aiogram import Bot
from aiogram.exceptions import TelegramAPIError
from aiogram.types import Message

from nori.db import Database
from nori.utils import (calc, esc, extract_media, fmt_duration, mock, parse_duration, send_media, KIND_TITLES)

HELP = (
    "<b>Команды в любом чате</b> (пишете сами, начиная с точки):\n"
    "<code>.mute [1ч]</code> — заглушить собеседника (его сообщения удаляются), <code>.unmute</code>\n"
    "<code>.save</code> (ответом на медиа) — прислать фото/видео/кружок/голосовое вам в личку, даже исчезающее\n"
    "<code>.ar on|off|текст</code> — автоответчик\n"
    "<code>.vip</code> — уведомлять в личку о каждом сообщении этого человека\n"
    "<code>.t имя</code> — вставить шаблон · <code>.tset имя текст</code> · <code>.tdel имя</code> · <code>.tlist</code>\n"
    "<code>.remind 30м текст</code> — напоминание в личку\n"
    "<code>.calc 2+2*3</code> — калькулятор прямо в сообщении\n"
    "<code>.type текст</code> — печатающийся текст · <code>.love</code> — сердечки\n"
    "<code>.dice [🎲🎯🏀⚽🎳🎰]</code> — отправить анимированный бросок\n"
    "<code>.mock текст</code> — «вОт ТаК» · <code>.shrug</code> — ¯\\_(ツ)_/¯\n"
    "<code>.stats</code> — самые активные чаты · <code>.log</code> — последние удалённые\n"
    "<code>.game</code> — ссылка на мини-игры · <code>.help</code> — эта справка"
)

DICE = ("🎲", "🎯", "🏀", "⚽", "🎳", "🎰")
LOVE_FRAMES = ["🤍", "🩷", "❤️", "🧡", "💛", "💚", "🩵", "💙", "💜", "🖤", "❤️‍🔥", "💕", "💞", "💖", "❤️"]
COMMANDS = {"mute", "unmute", "save", "ar", "vip", "t", "tset", "tdel", "tlist", "remind", "calc", "type", "love",
            "dice", "mock", "shrug", "stats", "log", "game", "help"}


def parse(text: str) -> tuple[str, str] | None:
    if not text or not text.startswith("."):
        return None
    head, _, rest = text[1:].partition(" ")
    head = head.lower()
    if head not in COMMANDS:
        return None
    return head, rest.strip()


async def _edit(bot: Bot, m: Message, text: str, html: bool = False) -> None:
    try:
        await bot.edit_message_text(text, business_connection_id=m.business_connection_id, chat_id=m.chat.id,
                                    message_id=m.message_id, parse_mode="HTML" if html else None)
    except TelegramAPIError:
        pass   # «message is not modified» и т.п. — не страшно


async def _delete(bot: Bot, m: Message, self_deleted: set) -> None:
    self_deleted.add((m.chat.id, m.message_id))
    try:
        await bot.delete_business_messages(m.business_connection_id, [m.message_id])
    except TelegramAPIError:
        pass


async def handle(m: Message, bot: Bot, db: Database, conn: dict, self_deleted: set, bot_username: str) -> bool:
    """True, если сообщение — команда и обработано."""
    parsed = parse(m.text or "")
    if parsed is None:
        return False
    cmd, arg = parsed
    owner, pm = conn["user_id"], conn["user_chat_id"]
    peer = m.chat.full_name or str(m.chat.id)
    cleanup = await db.flag(owner, "del_cmd")

    async def done(note: str | None = None) -> None:
        if cleanup:
            await _delete(bot, m, self_deleted)
        if note:
            await bot.send_message(pm, note)

    if cmd == "mute":
        sec = parse_duration(arg.split()[0]) if arg else None
        until = int(time.time()) + sec if sec else 0
        await db.mute(owner, m.chat.id, peer, until)
        warn = "" if conn["can_delete_all"] else "\n⚠️ У бота нет права удалять чужие сообщения — они не будут удаляться, но вы не получите от этого чата уведомлений Nori."
        await done(f"🔇 <b>{esc(peer)}</b> заглушен " + (f"на {fmt_duration(sec)}" if sec else "навсегда") +
                   ". Снять: <code>.unmute</code> в чате или в меню /start." + warn)
    elif cmd == "unmute":
        ok = await db.unmute(owner, m.chat.id)
        await done(f"🔊 <b>{esc(peer)}</b> больше не заглушен." if ok else f"{esc(peer)} и не был заглушен.")
    elif cmd == "save":
        src = m.reply_to_message
        kind, fid = extract_media(src) if src else (None, None)
        if not fid:
            await done("Ответьте командой <code>.save</code> на сообщение с медиа.")
            return True
        who = src.from_user.full_name if src.from_user else peer
        cap = f"🔥 Сохранено · {esc(who)} (чат «{esc(peer)}»)" + (f"\n{esc(src.caption)}" if src.caption else "")
        try:
            await send_media(bot, pm, kind, fid, cap)
            await done()
        except TelegramAPIError as e:
            await done(f"Не получилось достать медиа: {esc(str(e)[:150])}")
    elif cmd == "ar":
        a = arg.lower()
        if a in ("on", "вкл"):
            await db.set(owner, "ar_on", "1")
            await done("🤖 Автоответчик включён.")
        elif a in ("off", "выкл"):
            await db.set(owner, "ar_on", "0")
            await done("🤖 Автоответчик выключен.")
        elif arg:
            await db.set(owner, "ar_text", arg)
            await db.set(owner, "ar_on", "1")
            await done("🤖 Текст автоответа обновлён, автоответчик включён.")
        else:
            on = "включён" if await db.flag(owner, "ar_on") else "выключен"
            await done(f"🤖 Автоответчик {on}.\n<blockquote>{esc(await db.get(owner, 'ar_text'))}</blockquote>")
    elif cmd == "vip":
        on = await db.toggle_vip(owner, m.chat.id, peer)
        await done(f"⭐ {esc(peer)} — VIP: каждое сообщение будет дублироваться вам в личку." if on
                   else f"{esc(peer)} больше не VIP.")
    elif cmd == "t":
        text = await db.get_template(owner, arg) if arg else None
        if text is None:
            await done(f"Шаблон «{esc(arg)}» не найден. Список: <code>.tlist</code>")
        else:
            await _edit(bot, m, text)
    elif cmd == "tset":
        name, _, text = arg.partition(" ")
        if not name or not text.strip():
            await done("Формат: <code>.tset имя текст шаблона</code>")
        else:
            await db.set_template(owner, name, text.strip())
            await done(f"📝 Шаблон «{esc(name)}» сохранён. Вставка: <code>.t {esc(name)}</code>")
    elif cmd == "tdel":
        await db.del_template(owner, arg)
        await done(f"Шаблон «{esc(arg)}» удалён.")
    elif cmd == "tlist":
        ts = await db.templates(owner)
        await done("📝 <b>Шаблоны</b>\n" + ("\n".join(f"• <code>{esc(t['name'])}</code> — {esc(t['text'][:60])}" for t in ts)
                                           or "Пусто. Создать: <code>.tset имя текст</code>"))
    elif cmd == "remind":
        first, _, text = arg.partition(" ")
        sec = parse_duration(first)
        if not sec or not text.strip():
            await done("Формат: <code>.remind 30м купить хлеб</code> (с, м, ч, д)")
        else:
            await db.add_reminder(owner, pm, int(time.time()) + sec, text.strip())
            await done(f"⏰ Напомню через {fmt_duration(sec)}: {esc(text.strip())}")
    elif cmd == "calc":
        try:
            res = calc(arg)
            await _edit(bot, m, f"{arg} = {res}")
        except (ValueError, SyntaxError, ZeroDivisionError, OverflowError, RecursionError):
            await done("Не смог посчитать. Пример: <code>.calc (12+8)*3/4</code>")
    elif cmd == "type":
        if not arg:
            await done("Формат: <code>.type текст</code>")
        else:
            text = arg[:300]
            step = max(1, -(-len(text) // 14))
            for i in range(step, len(text), step):
                await _edit(bot, m, text[:i] + "▌")
                await asyncio.sleep(0.35)
            await _edit(bot, m, text)
    elif cmd == "love":
        for f in LOVE_FRAMES:
            await _edit(bot, m, f * 5)
            await asyncio.sleep(0.4)
        await _edit(bot, m, "❤️")
    elif cmd == "dice":
        emo = arg if arg in DICE else "🎲"
        await done()
        try:
            await bot.send_dice(m.chat.id, emoji=emo, business_connection_id=m.business_connection_id)
        except TelegramAPIError:
            pass
    elif cmd == "mock":
        await _edit(bot, m, mock(arg or "напишите текст после команды"))
    elif cmd == "shrug":
        await _edit(bot, m, (arg + " " if arg else "") + "¯\\_(ツ)_/¯")
    elif cmd == "stats":
        rows = await db.stats(owner)
        lines = [f"{i}. {esc(r['name'] or r['chat'])} — {r['theirs']} от них / {r['mine']} от вас" for i, r in enumerate(rows, 1)]
        await done("📊 <b>Самые активные чаты</b>\n" + ("\n".join(lines) or "Пока пусто."))
    elif cmd == "log":
        rows = await db.deleted_log(owner)
        lines = [f"🗑 <b>{esc(r['name'] or '?')}</b>: {esc((r['text'] or KIND_TITLES.get(r['kind'], 'медиа'))[:120])}" for r in rows]
        await done("<b>Последние удалённые</b>\n" + ("\n".join(lines) or "Пока ничего."))
    elif cmd == "game":
        await _edit(bot, m, f"🎮 Сыграем? https://t.me/{bot_username}?start=games")
    elif cmd == "help":
        await done(HELP)
    return True
