from __future__ import annotations

import os
import time

import aiosqlite

SCHEMA = """
CREATE TABLE IF NOT EXISTS connections(id TEXT PRIMARY KEY, user_id INTEGER, user_chat_id INTEGER,
    can_reply INTEGER, can_delete INTEGER, can_delete_all INTEGER, enabled INTEGER);
CREATE INDEX IF NOT EXISTS ix_conn_user ON connections(user_id);
CREATE TABLE IF NOT EXISTS messages(owner INTEGER, chat INTEGER, msg INTEGER, uid INTEGER, name TEXT, text TEXT,
    kind TEXT, file_id TEXT, ts INTEGER, PRIMARY KEY(owner, chat, msg));
CREATE INDEX IF NOT EXISTS ix_msg_ts ON messages(ts);
CREATE TABLE IF NOT EXISTS settings(owner INTEGER, key TEXT, value TEXT, PRIMARY KEY(owner, key));
CREATE TABLE IF NOT EXISTS mutes(owner INTEGER, chat INTEGER, until INTEGER, name TEXT, PRIMARY KEY(owner, chat));
CREATE TABLE IF NOT EXISTS triggers(id INTEGER PRIMARY KEY AUTOINCREMENT, owner INTEGER, keyword TEXT, reply TEXT);
CREATE TABLE IF NOT EXISTS templates(owner INTEGER, name TEXT, text TEXT, PRIMARY KEY(owner, name));
CREATE TABLE IF NOT EXISTS reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, owner INTEGER, pm_chat INTEGER, due INTEGER,
    text TEXT, done INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS ar_log(owner INTEGER, chat INTEGER, ts INTEGER, PRIMARY KEY(owner, chat));
CREATE TABLE IF NOT EXISTS vips(owner INTEGER, chat INTEGER, name TEXT, PRIMARY KEY(owner, chat));
CREATE TABLE IF NOT EXISTS stats(owner INTEGER, chat INTEGER, theirs INTEGER DEFAULT 0, mine INTEGER DEFAULT 0,
    name TEXT, PRIMARY KEY(owner, chat));
CREATE TABLE IF NOT EXISTS balances(user_id INTEGER PRIMARY KEY, name TEXT, coins INTEGER, last_bonus INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS deleted_log(id INTEGER PRIMARY KEY AUTOINCREMENT, owner INTEGER, chat INTEGER, name TEXT,
    text TEXT, kind TEXT, ts INTEGER);
"""

DEFAULTS = {
    "notify_deleted": "1",
    "notify_edited": "1",
    "save_media": "1",       # присылать входящее фото/видео/кружки/голосовые сразу (единственный надёжный способ поймать исчезающие)
    "ar_on": "0",
    "ar_text": "Привет, {name}! Сейчас я не могу ответить, напишу позже 🌙",
    "ar_mode": "always",     # always | schedule
    "ar_from": "22:00",
    "ar_to": "09:00",
    "ar_cooldown": "360",    # минут между автоответами одному человеку
    "tz": "3",               # часовой пояс (UTC+N)
    "del_cmd": "1",          # удалять сообщение-команду «.mute» и т.п.
}

START_COINS = 100
DAILY_BONUS = 100


class Database:
    def __init__(self, path: str):
        self.path = path
        self.db: aiosqlite.Connection | None = None
        self._conn_cache: dict[str, dict] = {}

    async def init(self) -> None:
        if self.path != ":memory:":
            os.makedirs(os.path.dirname(os.path.abspath(self.path)), exist_ok=True)
        self.db = await aiosqlite.connect(self.path)
        self.db.row_factory = aiosqlite.Row
        await self.db.execute("PRAGMA journal_mode=WAL")
        await self.db.executescript(SCHEMA)
        await self.db.commit()

    async def close(self) -> None:
        if self.db:
            await self.db.close()

    async def _run(self, sql, args=()):
        cur = await self.db.execute(sql, args)
        await self.db.commit()
        return cur

    async def _one(self, sql, args=()):
        cur = await self.db.execute(sql, args)
        return await cur.fetchone()

    async def _all(self, sql, args=()):
        cur = await self.db.execute(sql, args)
        return await cur.fetchall()

    # --- подключения ---
    async def save_connection(self, cid, user_id, user_chat_id, can_reply, can_delete, can_delete_all, enabled) -> None:
        await self._run("INSERT OR REPLACE INTO connections VALUES(?,?,?,?,?,?,?)",
                        (cid, user_id, user_chat_id, int(can_reply), int(can_delete), int(can_delete_all), int(enabled)))
        self._conn_cache[cid] = dict(id=cid, user_id=user_id, user_chat_id=user_chat_id, can_reply=int(can_reply),
                                     can_delete=int(can_delete), can_delete_all=int(can_delete_all), enabled=int(enabled))

    async def get_connection(self, cid: str) -> dict | None:
        if cid in self._conn_cache:
            return self._conn_cache[cid]
        row = await self._one("SELECT * FROM connections WHERE id=?", (cid,))
        if row:
            self._conn_cache[cid] = dict(row)
            return self._conn_cache[cid]
        return None

    async def connections_of(self, user_id: int) -> list[dict]:
        return [dict(r) for r in await self._all("SELECT * FROM connections WHERE user_id=?", (user_id,))]

    # --- настройки ---
    async def get(self, owner: int, key: str) -> str:
        row = await self._one("SELECT value FROM settings WHERE owner=? AND key=?", (owner, key))
        return row["value"] if row else DEFAULTS[key]

    async def set(self, owner: int, key: str, value: str) -> None:
        await self._run("INSERT OR REPLACE INTO settings VALUES(?,?,?)", (owner, key, value))

    async def flag(self, owner: int, key: str) -> bool:
        return (await self.get(owner, key)) == "1"

    async def toggle(self, owner: int, key: str) -> bool:
        new = "0" if await self.flag(owner, key) else "1"
        await self.set(owner, key, new)
        return new == "1"

    # --- кэш сообщений ---
    async def cache_message(self, owner, chat, msg, uid, name, text, kind, file_id, ts=None) -> None:
        await self._run("INSERT OR REPLACE INTO messages VALUES(?,?,?,?,?,?,?,?,?)",
                        (owner, chat, msg, uid, name, text, kind, file_id, ts or int(time.time())))

    async def get_message(self, owner, chat, msg) -> dict | None:
        row = await self._one("SELECT * FROM messages WHERE owner=? AND chat=? AND msg=?", (owner, chat, msg))
        return dict(row) if row else None

    async def prune(self, max_age_days: int = 14) -> None:
        await self._run("DELETE FROM messages WHERE ts<?", (int(time.time()) - max_age_days * 86400,))
        await self._run("DELETE FROM deleted_log WHERE ts<?", (int(time.time()) - 60 * 86400,))

    async def log_deleted(self, owner, chat, name, text, kind) -> None:
        await self._run("INSERT INTO deleted_log(owner,chat,name,text,kind,ts) VALUES(?,?,?,?,?,?)",
                        (owner, chat, name, text, kind, int(time.time())))

    async def deleted_log(self, owner: int, limit: int = 15) -> list[dict]:
        return [dict(r) for r in await self._all(
            "SELECT * FROM deleted_log WHERE owner=? ORDER BY id DESC LIMIT ?", (owner, limit))]

    # --- муты ---
    async def mute(self, owner, chat, name, until: int = 0) -> None:
        await self._run("INSERT OR REPLACE INTO mutes VALUES(?,?,?,?)", (owner, chat, until, name))

    async def unmute(self, owner, chat) -> bool:
        cur = await self._run("DELETE FROM mutes WHERE owner=? AND chat=?", (owner, chat))
        return cur.rowcount > 0

    async def is_muted(self, owner, chat) -> bool:
        row = await self._one("SELECT until FROM mutes WHERE owner=? AND chat=?", (owner, chat))
        if not row:
            return False
        if row["until"] and row["until"] <= time.time():
            await self.unmute(owner, chat)
            return False
        return True

    async def mutes(self, owner) -> list[dict]:
        now = int(time.time())
        await self._run("DELETE FROM mutes WHERE owner=? AND until>0 AND until<=?", (owner, now))
        return [dict(r) for r in await self._all("SELECT * FROM mutes WHERE owner=?", (owner,))]

    # --- триггеры / шаблоны ---
    async def add_trigger(self, owner, keyword, reply) -> None:
        await self._run("INSERT INTO triggers(owner,keyword,reply) VALUES(?,?,?)", (owner, keyword.lower(), reply))

    async def triggers(self, owner) -> list[dict]:
        return [dict(r) for r in await self._all("SELECT * FROM triggers WHERE owner=? ORDER BY id", (owner,))]

    async def del_trigger(self, owner, tid) -> None:
        await self._run("DELETE FROM triggers WHERE owner=? AND id=?", (owner, tid))

    async def set_template(self, owner, name, text) -> None:
        await self._run("INSERT OR REPLACE INTO templates VALUES(?,?,?)", (owner, name.lower(), text))

    async def get_template(self, owner, name) -> str | None:
        row = await self._one("SELECT text FROM templates WHERE owner=? AND name=?", (owner, name.lower()))
        return row["text"] if row else None

    async def templates(self, owner) -> list[dict]:
        return [dict(r) for r in await self._all("SELECT * FROM templates WHERE owner=? ORDER BY name", (owner,))]

    async def del_template(self, owner, name) -> None:
        await self._run("DELETE FROM templates WHERE owner=? AND name=?", (owner, name.lower()))

    # --- напоминания ---
    async def add_reminder(self, owner, pm_chat, due, text) -> int:
        cur = await self._run("INSERT INTO reminders(owner,pm_chat,due,text) VALUES(?,?,?,?)", (owner, pm_chat, due, text))
        return cur.lastrowid

    async def due_reminders(self, now: int) -> list[dict]:
        return [dict(r) for r in await self._all("SELECT * FROM reminders WHERE done=0 AND due<=?", (now,))]

    async def finish_reminder(self, rid) -> None:
        await self._run("UPDATE reminders SET done=1 WHERE id=?", (rid,))

    async def reminders(self, owner) -> list[dict]:
        return [dict(r) for r in await self._all("SELECT * FROM reminders WHERE owner=? AND done=0 ORDER BY due", (owner,))]

    async def del_reminder(self, owner, rid) -> None:
        await self._run("DELETE FROM reminders WHERE owner=? AND id=?", (owner, rid))

    # --- автоответчик ---
    async def last_autoreply(self, owner, chat) -> int:
        row = await self._one("SELECT ts FROM ar_log WHERE owner=? AND chat=?", (owner, chat))
        return row["ts"] if row else 0

    async def mark_autoreply(self, owner, chat, ts=None) -> None:
        await self._run("INSERT OR REPLACE INTO ar_log VALUES(?,?,?)", (owner, chat, ts or int(time.time())))

    # --- VIP ---
    async def toggle_vip(self, owner, chat, name) -> bool:
        if await self._one("SELECT 1 FROM vips WHERE owner=? AND chat=?", (owner, chat)):
            await self._run("DELETE FROM vips WHERE owner=? AND chat=?", (owner, chat))
            return False
        await self._run("INSERT INTO vips VALUES(?,?,?)", (owner, chat, name))
        return True

    async def is_vip(self, owner, chat) -> bool:
        return bool(await self._one("SELECT 1 FROM vips WHERE owner=? AND chat=?", (owner, chat)))

    # --- статистика ---
    async def count(self, owner, chat, name, mine: bool) -> None:
        col = "mine" if mine else "theirs"
        await self._run(
            f"INSERT INTO stats(owner,chat,{col},name) VALUES(?,?,1,?) "
            f"ON CONFLICT(owner,chat) DO UPDATE SET {col}={col}+1, name=excluded.name", (owner, chat, name))

    async def stats(self, owner, limit=10) -> list[dict]:
        return [dict(r) for r in await self._all(
            "SELECT * FROM stats WHERE owner=? ORDER BY theirs+mine DESC LIMIT ?", (owner, limit))]

    # --- монеты ---
    async def balance(self, uid: int, name: str = "") -> int:
        row = await self._one("SELECT coins FROM balances WHERE user_id=?", (uid,))
        if row is None:
            await self._run("INSERT INTO balances VALUES(?,?,?,0)", (uid, name, START_COINS))
            return START_COINS
        if name:
            await self._run("UPDATE balances SET name=? WHERE user_id=?", (name, uid))
        return row["coins"]

    async def add_coins(self, uid: int, n: int) -> None:
        await self.balance(uid)
        await self._run("UPDATE balances SET coins=coins+? WHERE user_id=?", (n, uid))

    async def try_spend(self, uid: int, n: int) -> bool:
        await self.balance(uid)
        cur = await self._run("UPDATE balances SET coins=coins-? WHERE user_id=? AND coins>=?", (n, uid, n))
        return cur.rowcount > 0

    async def claim_bonus(self, uid: int, now: int) -> int | None:
        await self.balance(uid)
        day = now // 86400
        cur = await self._run("UPDATE balances SET coins=coins+?, last_bonus=? WHERE user_id=? AND last_bonus<?",
                              (DAILY_BONUS, day, uid, day))
        return DAILY_BONUS if cur.rowcount else None

    async def top(self, limit=10) -> list[tuple[str, int]]:
        return [(r["name"] or "игрок", r["coins"]) for r in await self._all(
            "SELECT name, coins FROM balances ORDER BY coins DESC LIMIT ?", (limit,))]
