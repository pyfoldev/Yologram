//! База данных (SQLite). Порт `bot/db.py` — та же схема, те же методы, совместимо с существующими файлами БД.
//! PostgreSQL-бэкенд оригинала не переносится (ADR-6: только SQLite).
use rusqlite::{params_from_iter, types::Value as SqlValue, types::ValueRef, Connection, OptionalExtension};
use serde_json::{json, Map, Value};
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

pub const HOUR: i64 = 3600;
pub const DAY: i64 = 86400;
pub const ROLLUP_DAYS: i64 = 90;
pub const START_BALANCE: i64 = 1000;
pub const BONUS_AMOUNT: i64 = 500;
pub const HOT_TTL: f64 = 30.0;
pub const SETTINGS_TTL: f64 = 10.0;

pub type Row = Map<String, Value>;
pub type Res<T> = rusqlite::Result<T>;

pub fn now_ts() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// Значение настройки: число или строка (как `int | str` в Python).
#[derive(Debug, Clone, PartialEq)]
pub enum Setting {
    Int(i64),
    Str(String),
}
impl Setting {
    pub fn int(&self) -> i64 {
        match self {
            Setting::Int(i) => *i,
            Setting::Str(s) => s.parse().unwrap_or(0),
        }
    }
    pub fn str(&self) -> String {
        match self {
            Setting::Int(i) => i.to_string(),
            Setting::Str(s) => s.clone(),
        }
    }
}
impl From<i64> for Setting {
    fn from(v: i64) -> Self {
        Setting::Int(v)
    }
}
impl From<&str> for Setting {
    fn from(v: &str) -> Self {
        Setting::Str(v.into())
    }
}
pub type Settings = HashMap<String, Setting>;

pub const GLOBAL_DEFAULTS: [(&str, i64); 5] = [("g_ai", 1), ("g_stt", 1), ("g_nsfw", 1), ("g_aimod", 1), ("maintenance", 0)];

pub fn default_settings() -> Settings {
    let mut m = Settings::new();
    for (k, v) in [
        ("captcha_on", 1), ("captcha_timeout", 180), ("warn_limit", 3), ("warn_ttl_days", 30), ("log_chat_id", 0),
        ("report_on", 1), ("welcome_on", 1), ("ai_mod_conf", 75), ("games_on", 1), ("mod_on", 1), ("stats_on", 1),
        ("rally_on", 1), ("quotes_on", 1), ("mog_on", 1), ("giveaway_on", 1), ("suggest_on", 1), ("img_on", 1),
        ("dl_on", 1), ("dl_auto", 1), ("fun_on", 1), ("rp_on", 1), ("rep_on", 1), ("spam_on", 0), ("spam_flood_n", 5),
        ("spam_flood_sec", 10), ("spam_dup", 1), ("spam_mentions", 5), ("spam_forward", 0), ("spam_ai", 0),
        ("raid_on", 0), ("raid_n", 5), ("raid_sec", 60), ("raid_minutes", 10), ("nsfw_on", 0), ("nsfw_threshold", 55),
        ("nsfw_ai", 0), ("transcribe_on", 0), ("transcribe_max", 180), ("ai_on", 1),
    ] {
        m.insert(k.into(), Setting::Int(v));
    }
    for (k, v) in [
        ("ai_mod", "off"), ("ai_mod_action", "delete"), ("tz", "Europe/Moscow"), ("links_mode", "newbies"),
        ("filter_action", "delete"), ("spam_action", "delete"), ("raid_action", "captcha"), ("nsfw_action", "delete"),
        ("ai_chat", "off"), ("ai_chat_style", "casual"),
    ] {
        m.insert(k.into(), Setting::Str(v.into()));
    }
    m
}

pub const SCHEMA: &str = include_str!("schema.sql");

fn val(v: ValueRef) -> Value {
    match v {
        ValueRef::Null => Value::Null,
        ValueRef::Integer(i) => json!(i),
        ValueRef::Real(f) => json!(f),
        ValueRef::Text(t) => Value::String(String::from_utf8_lossy(t).into_owned()),
        ValueRef::Blob(b) => Value::String(hex::encode(b)),
    }
}

/// Приведение к параметру SQL.
pub trait Param {
    fn param(self) -> SqlValue;
}
impl Param for &str {
    fn param(self) -> SqlValue {
        SqlValue::Text(self.into())
    }
}
impl Param for &String {
    fn param(self) -> SqlValue {
        SqlValue::Text(self.clone())
    }
}
impl Param for String {
    fn param(self) -> SqlValue {
        SqlValue::Text(self)
    }
}
impl Param for i64 {
    fn param(self) -> SqlValue {
        SqlValue::Integer(self)
    }
}
impl Param for i32 {
    fn param(self) -> SqlValue {
        SqlValue::Integer(self as i64)
    }
}
impl Param for usize {
    fn param(self) -> SqlValue {
        SqlValue::Integer(self as i64)
    }
}
impl Param for SqlValue {
    fn param(self) -> SqlValue {
        self
    }
}
impl<T: Param> Param for Option<T> {
    fn param(self) -> SqlValue {
        self.map_or(SqlValue::Null, Param::param)
    }
}
#[macro_export]
macro_rules! args {
    ($($x:expr),* $(,)?) => { vec![$($crate::db::Param::param($x)),*] };
}
pub use args;

pub struct Database {
    pub conn: Mutex<Connection>,
    pub path: String,
    t0: Instant,
    settings_cache: Mutex<HashMap<i64, (f64, Settings)>>,
    lists_version: Mutex<HashMap<i64, i64>>,
    msg_buf: Mutex<HashMap<(i64, i64, i64), i64>>,
    rank_cache: Mutex<HashMap<(i64, i64), (f64, i64)>>,
    joined_cache: Mutex<HashMap<(i64, i64), (f64, Option<i64>)>>,
    user_cache: Mutex<HashMap<i64, (Option<String>, String)>>,
    pub usernames: Mutex<HashMap<i64, Option<String>>>,
}

impl Database {
    pub fn connect(path: &str) -> Res<Self> {
        if let Some(d) = std::path::Path::new(path).parent() {
            if !d.as_os_str().is_empty() {
                let _ = std::fs::create_dir_all(d);
            }
        }
        let conn = if path == ":memory:" { Connection::open_in_memory()? } else { Connection::open(path)? };
        conn.create_scalar_function("lower_py", 1, rusqlite::functions::FunctionFlags::SQLITE_DETERMINISTIC, |c| {
            Ok(match c.get_raw(0) {
                ValueRef::Text(t) => SqlValue::Text(String::from_utf8_lossy(t).to_lowercase()),
                other => other.into(),
            })
        })?;
        let _: String = conn.query_row("PRAGMA journal_mode=WAL", [], |r| r.get(0)).unwrap_or_default();
        conn.execute_batch("PRAGMA synchronous=NORMAL;")?;
        conn.execute_batch(SCHEMA)?;
        let cols = |t: &str| -> Res<Vec<String>> {
            let mut st = conn.prepare(&format!("PRAGMA table_info({t})"))?;
            let v = st.query_map([], |r| r.get::<_, String>(1))?.collect::<Res<Vec<_>>>();
            v
        };
        let c = cols("captchas")?;
        for (name, ddl) in [("kind", "kind TEXT DEFAULT 'member'"), ("level", "level INTEGER DEFAULT 0"), ("attempts", "attempts INTEGER DEFAULT 0")] {
            if !c.iter().any(|x| x == name) {
                conn.execute(&format!("ALTER TABLE captchas ADD COLUMN {ddl}"), [])?;
            }
        }
        if !cols("mod_log")?.iter().any(|x| x == "duration") {
            conn.execute("ALTER TABLE mod_log ADD COLUMN duration INTEGER", [])?;
        }
        let db = Database {
            conn: Mutex::new(conn),
            path: path.into(),
            t0: Instant::now(),
            settings_cache: Default::default(),
            lists_version: Default::default(),
            msg_buf: Default::default(),
            rank_cache: Default::default(),
            joined_cache: Default::default(),
            user_cache: Default::default(),
            usernames: Default::default(),
        };
        db.migrate_legacy_warns(None)?;
        db.optimize()?;
        Ok(db)
    }

    fn clock(&self) -> f64 {
        self.t0.elapsed().as_secs_f64()
    }

    // ---------- низкоуровневые помощники ----------
    pub fn exec(&self, sql: &str, a: Vec<SqlValue>) -> Res<usize> {
        self.conn.lock().unwrap().execute(sql, params_from_iter(a))
    }
    pub fn insert(&self, sql: &str, a: Vec<SqlValue>) -> Res<i64> {
        let c = self.conn.lock().unwrap();
        c.execute(sql, params_from_iter(a))?;
        Ok(c.last_insert_rowid())
    }
    pub fn rows(&self, sql: &str, a: Vec<SqlValue>) -> Res<Vec<Row>> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare_cached(sql)?;
        let names: Vec<String> = st.column_names().iter().map(|s| s.to_string()).collect();
        let it = st.query_map(params_from_iter(a), |r| {
            let mut m = Row::new();
            for (i, n) in names.iter().enumerate() {
                m.insert(n.clone(), val(r.get_ref(i)?));
            }
            Ok(m)
        })?;
        it.collect()
    }
    pub fn row(&self, sql: &str, a: Vec<SqlValue>) -> Res<Option<Row>> {
        Ok(self.rows(sql, a)?.into_iter().next())
    }
    pub fn scalar_i(&self, sql: &str, a: Vec<SqlValue>) -> Res<i64> {
        Ok(self.conn.lock().unwrap().query_row(sql, params_from_iter(a), |r| r.get::<_, Option<i64>>(0)).optional()?.flatten().unwrap_or(0))
    }
    fn exists(&self, sql: &str, a: Vec<SqlValue>) -> Res<bool> {
        Ok(self.conn.lock().unwrap().query_row(sql, params_from_iter(a), |_| Ok(())).optional()?.is_some())
    }

    pub fn close(&self) -> Res<()> {
        self.flush_messages()
    }

    // ---------- статистика ----------
    pub fn record_message(&self, chat_id: i64, user_id: i64, ts: i64) {
        *self.msg_buf.lock().unwrap().entry((chat_id, user_id, ts / HOUR * HOUR)).or_insert(0) += 1;
    }

    pub fn flush_messages(&self) -> Res<()> {
        let buf = std::mem::take(&mut *self.msg_buf.lock().unwrap());
        if buf.is_empty() {
            return Ok(());
        }
        let mut c = self.conn.lock().unwrap();
        let res: Res<()> = (|| {
            let tx = c.transaction()?;
            for ((chat, user, hour), n) in &buf {
                tx.execute(
                    "INSERT INTO msg_hourly VALUES(?,?,?,?) ON CONFLICT(chat_id,user_id,hour_ts) DO UPDATE SET n=msg_hourly.n+excluded.n",
                    rusqlite::params![chat, user, hour, n],
                )?;
            }
            tx.commit()
        })();
        if res.is_err() {
            let mut b = self.msg_buf.lock().unwrap();
            for (k, n) in buf {
                *b.entry(k).or_insert(0) += n;
            }
        }
        res
    }

    fn since(since_ts: Option<i64>) -> (i64, i64) {
        match since_ts {
            None => (-1, -1),
            Some(t) => (t / HOUR * HOUR, t),
        }
    }

    pub fn count_by_user(&self, chat_id: i64, since_ts: Option<i64>, limit: usize) -> Res<(i64, Vec<(i64, i64)>)> {
        self.flush_messages()?;
        let (sh, sd) = Self::since(since_ts);
        let rows = self.rows(
            "SELECT user_id, SUM(n) AS n FROM (SELECT user_id, n FROM msg_hourly WHERE chat_id=? AND hour_ts>=? UNION ALL \
             SELECT user_id, n FROM msg_daily WHERE chat_id=? AND day_ts>=?) GROUP BY user_id ORDER BY n DESC, user_id",
            args![chat_id, sh, chat_id, sd],
        )?;
        let all: Vec<(i64, i64)> = rows.iter().map(|r| (r["user_id"].as_i64().unwrap(), r["n"].as_i64().unwrap_or(0))).collect();
        Ok((all.iter().map(|x| x.1).sum(), all.into_iter().take(limit).collect()))
    }

    pub fn activity_series(&self, chat_id: i64, since_ts: i64, bucket: i64) -> Res<Vec<(i64, i64)>> {
        self.flush_messages()?;
        let rows = self.rows(
            "SELECT ts, SUM(n) AS n FROM (SELECT hour_ts/? *? AS ts, n FROM msg_hourly WHERE chat_id=? AND hour_ts>=? UNION ALL \
             SELECT day_ts/? *? AS ts, n FROM msg_daily WHERE chat_id=? AND day_ts>=?) t GROUP BY ts ORDER BY ts",
            args![bucket, bucket, chat_id, since_ts / HOUR * HOUR, bucket, bucket, chat_id, since_ts],
        )?;
        Ok(rows.iter().map(|r| (r["ts"].as_i64().unwrap(), r["n"].as_i64().unwrap_or(0))).collect())
    }

    pub fn user_total(&self, chat_id: i64, user_id: i64, since_ts: Option<i64>) -> Res<i64> {
        self.flush_messages()?;
        let (sh, sd) = Self::since(since_ts);
        self.scalar_i(
            "SELECT COALESCE(SUM(n),0) FROM (SELECT n FROM msg_hourly WHERE chat_id=? AND user_id=? AND hour_ts>=? UNION ALL \
             SELECT n FROM msg_daily WHERE chat_id=? AND user_id=? AND day_ts>=?)",
            args![chat_id, user_id, sh, chat_id, user_id, sd],
        )
    }

    pub fn rollup_old(&self, now: i64) -> Res<()> {
        self.flush_messages()?;
        let border = now - ROLLUP_DAYS * DAY;
        self.exec(
            "INSERT INTO msg_daily SELECT chat_id, user_id, day, SUM(n) FROM (SELECT chat_id, user_id, hour_ts/? *? AS day, n \
             FROM msg_hourly WHERE hour_ts<?) t GROUP BY chat_id, user_id, day ON CONFLICT(chat_id,user_id,day_ts) DO UPDATE SET n=msg_daily.n+excluded.n",
            args![DAY, DAY, border],
        )?;
        self.exec("DELETE FROM msg_hourly WHERE hour_ts<?", args![border])?;
        Ok(())
    }

    pub fn optimize(&self) -> Res<()> {
        let c = self.conn.lock().unwrap();
        c.execute_batch("PRAGMA optimize=0x10002")
    }

    pub fn prune_old(&self, now: i64) -> Res<()> {
        self.exec("DELETE FROM rep_log WHERE ts<?", args![now - 2 * DAY])?;
        self.exec("DELETE FROM usage WHERE day<?", args![now / DAY - 7])?;
        self.exec("DELETE FROM aimod_events WHERE ts<?", args![now - 7 * DAY])?;
        self.gw_prune(now)
    }

    // ---------- пользователи ----------
    fn remember_username(&self, user_id: i64, username: Option<&str>) {
        let mut u = self.usernames.lock().unwrap();
        if u.len() > 200_000 {
            u.clear();
        }
        let n = username.unwrap_or("").trim_start_matches('@').to_string();
        u.insert(user_id, if n.is_empty() { None } else { Some(n) });
    }

    pub fn remember_user(&self, user_id: i64, username: Option<&str>, name: &str) -> Res<()> {
        let un = username.unwrap_or("").trim_start_matches('@').to_lowercase();
        let v = (if un.is_empty() { None } else { Some(un) }, name.to_string());
        self.remember_username(user_id, username);
        if self.user_cache.lock().unwrap().get(&user_id) == Some(&v) {
            return Ok(());
        }
        self.exec(
            "INSERT INTO users VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE SET username=excluded.username, name=excluded.name",
            args![user_id, v.0.clone(), v.1.clone()],
        )?;
        let mut uc = self.user_cache.lock().unwrap();
        if uc.len() > 50_000 {
            uc.clear();
        }
        uc.insert(user_id, v);
        Ok(())
    }

    pub fn get_username(&self, user_id: i64) -> Res<Option<String>> {
        Ok(self.row("SELECT username FROM users WHERE user_id=?", args![user_id])?.and_then(|r| r["username"].as_str().filter(|s| !s.is_empty()).map(String::from)))
    }

    pub fn find_user(&self, username: &str) -> Res<Option<i64>> {
        Ok(self.row("SELECT user_id FROM users WHERE username=?", args![username.trim_start_matches('@').to_lowercase()])?.and_then(|r| r["user_id"].as_i64()))
    }

    pub fn user_name(&self, user_id: i64) -> Res<Option<String>> {
        let Some(r) = self.row("SELECT name, username FROM users WHERE user_id=?", args![user_id])? else { return Ok(None) };
        self.remember_username(user_id, r["username"].as_str());
        Ok(r["name"].as_str().map(String::from))
    }

    // ---------- чаты ----------
    pub fn upsert_chat(&self, chat_id: i64, title: Option<&str>, ctype: &str, username: Option<&str>, ts: i64) -> Res<()> {
        self.exec(
            "INSERT INTO chats(chat_id,title,type,username,added_at,last_seen,active) VALUES(?,?,?,?,?,?,1) ON CONFLICT(chat_id) DO UPDATE SET \
             title=excluded.title, type=excluded.type, username=excluded.username, last_seen=excluded.last_seen, active=1",
            args![chat_id, title.map(String::from), ctype, username.map(String::from), ts, ts],
        )?;
        Ok(())
    }
    pub fn set_chat_active(&self, chat_id: i64, active: bool) -> Res<()> {
        self.exec("UPDATE chats SET active=? WHERE chat_id=?", args![active as i64, chat_id])?;
        Ok(())
    }
    pub fn count_chats(&self, active_only: bool) -> Res<i64> {
        self.scalar_i(&format!("SELECT COUNT(*) FROM chats{}", if active_only { " WHERE active=1" } else { "" }), args![])
    }
    pub fn list_chats(&self, offset: i64, limit: i64, active_only: bool) -> Res<Vec<Row>> {
        self.rows(
            &format!("SELECT * FROM chats{} ORDER BY last_seen DESC, chat_id LIMIT ? OFFSET ?", if active_only { " WHERE active=1" } else { "" }),
            args![limit, offset],
        )
    }
    pub fn get_chat(&self, chat_id: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM chats WHERE chat_id=?", args![chat_id])
    }

    // ---------- поиск пользователей ----------
    pub fn count_users(&self) -> Res<i64> {
        self.scalar_i("SELECT COUNT(*) FROM users", args![])
    }
    pub fn search_users(&self, query: &str, limit: i64) -> Res<Vec<Row>> {
        let q = query.trim().trim_start_matches('@').to_lowercase();
        if q.is_empty() {
            return Ok(vec![]);
        }
        let esc = q.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_");
        let id: i64 = if q.chars().all(|c| c.is_ascii_digit()) { q.parse().unwrap_or(-1) } else { -1 };
        let like = format!("%{esc}%");
        self.rows(
            "SELECT user_id, username, name FROM users WHERE user_id=? OR username LIKE ? ESCAPE '\\' OR lower_py(name) LIKE ? ESCAPE '\\' ORDER BY user_id LIMIT ?",
            args![id, like.clone(), like, limit],
        )
    }
    pub fn user_card(&self, user_id: i64) -> Res<Option<Row>> {
        let Some(mut card) = self.row("SELECT user_id, username, name FROM users WHERE user_id=?", args![user_id])? else { return Ok(None) };
        self.flush_messages()?;
        card.insert("chats".into(), json!(self.scalar_i("SELECT COUNT(*) FROM chat_members WHERE user_id=?", args![user_id])?));
        card.insert(
            "messages".into(),
            json!(self.scalar_i(
                "SELECT (SELECT COALESCE(SUM(n),0) FROM msg_hourly WHERE user_id=?) + (SELECT COALESCE(SUM(n),0) FROM msg_daily WHERE user_id=?)",
                args![user_id, user_id]
            )?),
        );
        Ok(Some(card))
    }

    // ---------- блокировки, глобальные флаги, журнал владельца ----------
    pub fn bot_ban(&self, kind: &str, id: i64, reason: &str, ts: i64) -> Res<()> {
        self.exec("INSERT INTO bot_bans VALUES(?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET reason=excluded.reason, ts=excluded.ts", args![kind, id, reason, ts])?;
        Ok(())
    }
    pub fn bot_unban(&self, kind: &str, id: i64) -> Res<()> {
        self.exec("DELETE FROM bot_bans WHERE kind=? AND id=?", args![kind, id])?;
        Ok(())
    }
    pub fn list_bot_bans(&self, kind: &str) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM bot_bans WHERE kind=? ORDER BY ts DESC", args![kind])
    }
    pub fn load_bans(&self) -> Res<(HashSet<i64>, HashSet<i64>)> {
        let rows = self.rows("SELECT kind, id FROM bot_bans", args![])?;
        let pick = |k: &str| rows.iter().filter(|r| r["kind"] == k).filter_map(|r| r["id"].as_i64()).collect::<HashSet<_>>();
        Ok((pick("user"), pick("chat")))
    }
    pub fn get_global(&self) -> Res<HashMap<String, i64>> {
        let mut out: HashMap<String, i64> = GLOBAL_DEFAULTS.iter().map(|(k, v)| (k.to_string(), *v)).collect();
        for r in self.rows("SELECT key, value FROM settings WHERE chat_id=0", args![])? {
            let k = r["key"].as_str().unwrap_or("").to_string();
            if out.contains_key(&k) {
                out.insert(k, r["value"].as_str().and_then(|s| s.parse().ok()).unwrap_or(0));
            }
        }
        Ok(out)
    }
    pub fn set_global(&self, key: &str, value: i64) -> Result<(), String> {
        if !GLOBAL_DEFAULTS.iter().any(|(k, _)| *k == key) {
            return Err(format!("KeyError: {key}"));
        }
        self.exec("INSERT INTO settings VALUES(0,?,?) ON CONFLICT(chat_id,key) DO UPDATE SET value=excluded.value", args![key, value.to_string()]).map_err(|e| e.to_string())?;
        Ok(())
    }
    pub fn log_owner(&self, owner_id: i64, action: &str, detail: &str, ts: i64) -> Res<()> {
        self.exec("INSERT INTO owner_log(ts,owner_id,action,detail) VALUES(?,?,?,?)", args![ts, owner_id, action, detail])?;
        Ok(())
    }
    pub fn recent_owner_log(&self, limit: i64) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM owner_log ORDER BY id DESC LIMIT ?", args![limit])
    }
    pub fn size_bytes(&self) -> i64 {
        std::fs::metadata(&self.path).map(|m| m.len() as i64).unwrap_or(0)
    }
    pub fn backup_to(&self, path: &str) -> Res<()> {
        self.flush_messages()?;
        let c = self.conn.lock().unwrap();
        let _ = std::fs::remove_file(path);
        c.execute("VACUUM INTO ?", [path])?;
        Ok(())
    }

    // ---------- участники ----------
    pub fn touch_member(&self, chat_id: i64, user_id: i64, ts: i64, joined: bool) -> Res<()> {
        self.joined_cache.lock().unwrap().remove(&(chat_id, user_id));
        self.exec(
            "INSERT INTO chat_members(chat_id,user_id,first_seen,last_seen,joined_at,active) VALUES(?,?,?,?,?,1) ON CONFLICT(chat_id,user_id) DO UPDATE SET \
             last_seen=excluded.last_seen, active=1, joined_at=CASE WHEN ?=1 THEN excluded.joined_at ELSE chat_members.joined_at END",
            args![chat_id, user_id, ts, ts, if joined { Some(ts) } else { None }, joined as i64],
        )?;
        Ok(())
    }
    pub fn set_member_active(&self, chat_id: i64, user_id: i64, active: bool) -> Res<()> {
        self.exec("UPDATE chat_members SET active=? WHERE chat_id=? AND user_id=?", args![active as i64, chat_id, user_id])?;
        Ok(())
    }
    pub fn member_joined_at(&self, chat_id: i64, user_id: i64) -> Res<Option<i64>> {
        if let Some((t, v)) = self.joined_cache.lock().unwrap().get(&(chat_id, user_id)) {
            if self.clock() - t < HOT_TTL {
                return Ok(*v);
            }
        }
        let v = self.row("SELECT joined_at FROM chat_members WHERE chat_id=? AND user_id=?", args![chat_id, user_id])?.and_then(|r| r["joined_at"].as_i64());
        let mut c = self.joined_cache.lock().unwrap();
        if c.len() > 20000 {
            c.clear();
        }
        c.insert((chat_id, user_id), (self.clock(), v));
        Ok(v)
    }
    pub fn random_member(&self, chat_id: i64) -> Res<Option<(i64, String)>> {
        Ok(self.random_members(chat_id, 1, &[])?.into_iter().next())
    }
    pub fn random_members(&self, chat_id: i64, n: i64, exclude: &[i64]) -> Res<Vec<(i64, String)>> {
        let clause = if exclude.is_empty() { String::new() } else { format!(" AND m.user_id NOT IN ({})", vec!["?"; exclude.len()].join(",")) };
        let mut a = args![chat_id];
        a.extend(exclude.iter().map(|x| Param::param(*x)));
        a.push(Param::param(n));
        let rows = self.rows(
            &format!("SELECT m.user_id, COALESCE(u.name, 'id'||m.user_id) AS name FROM chat_members m LEFT JOIN users u ON u.user_id=m.user_id WHERE m.chat_id=? AND m.active=1{clause} ORDER BY RANDOM() LIMIT ?"),
            a,
        )?;
        Ok(rows.iter().map(|r| (r["user_id"].as_i64().unwrap(), r["name"].as_str().unwrap_or("").to_string())).collect())
    }
    /// (начислено или None, сколько секунд ждать).
    pub fn claim_farm(&self, chat_id: i64, user_id: i64, now: i64, cooldown: i64, amount: i64) -> Res<(Option<i64>, i64)> {
        let n = self.exec(
            "INSERT INTO farm VALUES(?,?,?) ON CONFLICT(chat_id,user_id) DO UPDATE SET last_ts=excluded.last_ts WHERE farm.last_ts<=?",
            args![chat_id, user_id, now, now - cooldown],
        )?;
        if n == 1 {
            self.change_balance(chat_id, user_id, amount)?;
            return Ok((Some(amount), 0));
        }
        let last = self.scalar_i("SELECT last_ts FROM farm WHERE chat_id=? AND user_id=?", args![chat_id, user_id])?;
        Ok((None, (cooldown - (now - last)).max(1)))
    }
    pub fn member_count(&self, chat_id: i64) -> Res<i64> {
        self.scalar_i("SELECT COUNT(*) FROM chat_members WHERE chat_id=? AND active=1", args![chat_id])
    }

    // ---------- квоты ----------
    pub fn take_quota(&self, chat_id: i64, kind: &str, now: i64, limit: i64) -> Res<bool> {
        Ok(self.exec("INSERT INTO usage VALUES(?,?,?,1) ON CONFLICT(chat_id,kind,day) DO UPDATE SET n=usage.n+1 WHERE usage.n<?", args![chat_id, kind, now / DAY, limit])? == 1)
    }
    pub fn refund_quota(&self, chat_id: i64, kind: &str, now: i64) -> Res<()> {
        self.exec("UPDATE usage SET n=n-1 WHERE chat_id=? AND kind=? AND day=? AND n>0", args![chat_id, kind, now / DAY])?;
        Ok(())
    }

    // ---------- огород ----------
    pub fn get_garden(&self, chat_id: i64, user_id: i64) -> Res<Option<crate::games::Garden>> {
        Ok(self
            .row("SELECT data FROM garden WHERE chat_id=? AND user_id=?", args![chat_id, user_id])?
            .and_then(|r| serde_json::from_str(r["data"].as_str()?).ok()))
    }
    pub fn save_garden(&self, chat_id: i64, user_id: i64, g: &crate::games::Garden) -> Res<()> {
        self.exec("INSERT INTO garden VALUES(?,?,?) ON CONFLICT(chat_id,user_id) DO UPDATE SET data=excluded.data", args![chat_id, user_id, serde_json::to_string(g).unwrap()])?;
        Ok(())
    }

    // ---------- предупреждения и журнал модерации ----------
    pub fn add_warn(&self, chat_id: i64, user_id: i64, now: Option<i64>, ttl: Option<i64>, reason: &str, actor: i64) -> Res<i64> {
        let now = now.unwrap_or_else(now_ts);
        self.exec(
            "INSERT INTO warn_events(chat_id,user_id,ts,expires_at,reason,actor) VALUES(?,?,?,?,?,?)",
            args![chat_id, user_id, now, ttl.map(|t| now + t), reason, actor],
        )?;
        self.get_warns(chat_id, user_id, Some(now))
    }
    pub fn remove_warn(&self, chat_id: i64, user_id: i64, now: Option<i64>) -> Res<i64> {
        let now = now.unwrap_or_else(now_ts);
        self.exec(
            "UPDATE warn_events SET expires_at=? WHERE id=(SELECT MAX(id) FROM warn_events WHERE chat_id=? AND user_id=? AND (expires_at IS NULL OR expires_at>?))",
            args![now, chat_id, user_id, now],
        )?;
        self.get_warns(chat_id, user_id, Some(now))
    }
    pub fn reset_warns(&self, chat_id: i64, user_id: i64, now: Option<i64>) -> Res<()> {
        let now = now.unwrap_or_else(now_ts);
        self.exec("UPDATE warn_events SET expires_at=? WHERE chat_id=? AND user_id=? AND (expires_at IS NULL OR expires_at>?)", args![now, chat_id, user_id, now])?;
        Ok(())
    }
    pub fn get_warns(&self, chat_id: i64, user_id: i64, now: Option<i64>) -> Res<i64> {
        let now = now.unwrap_or_else(now_ts);
        self.scalar_i("SELECT COUNT(*) FROM warn_events WHERE chat_id=? AND user_id=? AND (expires_at IS NULL OR expires_at>?)", args![chat_id, user_id, now])
    }
    pub fn migrate_legacy_warns(&self, now: Option<i64>) -> Res<()> {
        let now = now.unwrap_or_else(now_ts);
        for r in self.rows("SELECT chat_id, user_id, n FROM warns WHERE n>0", args![])? {
            for _ in 0..r["n"].as_i64().unwrap_or(0) {
                self.exec(
                    "INSERT INTO warn_events(chat_id,user_id,ts,expires_at,reason,actor) VALUES(?,?,?,NULL,?,0)",
                    args![r["chat_id"].as_i64().unwrap(), r["user_id"].as_i64().unwrap(), now, "перенесено из старой версии"],
                )?;
            }
        }
        self.exec("DELETE FROM warns", args![])?;
        Ok(())
    }
    pub fn log_action(&self, chat_id: i64, actor_id: i64, target_id: i64, action: &str, reason: &str, ts: i64, duration: Option<i64>) -> Res<()> {
        self.exec("INSERT INTO mod_log(chat_id,actor_id,target_id,action,reason,ts,duration) VALUES(?,?,?,?,?,?,?)", args![chat_id, actor_id, target_id, action, reason, ts, duration])?;
        Ok(())
    }

    // ---------- фильтры и белый список ссылок ----------
    pub fn lists_version(&self, chat_id: i64) -> i64 {
        *self.lists_version.lock().unwrap().get(&chat_id).unwrap_or(&0)
    }
    fn bump(&self, chat_id: i64) {
        *self.lists_version.lock().unwrap().entry(chat_id).or_insert(0) += 1;
    }
    pub fn add_filter(&self, chat_id: i64, kind: &str, pattern: &str, created_by: i64, ts: i64, limit: usize) -> Res<Option<i64>> {
        let rows = self.rows("SELECT kind, pattern FROM filters WHERE chat_id=?", args![chat_id])?;
        if rows.len() >= limit || rows.iter().any(|r| r["kind"] == kind && r["pattern"] == pattern) {
            return Ok(None);
        }
        let id = self.insert("INSERT INTO filters(chat_id,kind,pattern,action,created_by,ts) VALUES(?,?,?,'',?,?)", args![chat_id, kind, pattern, created_by, ts])?;
        self.bump(chat_id);
        Ok(Some(id))
    }
    pub fn list_filters(&self, chat_id: i64) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM filters WHERE chat_id=? ORDER BY id", args![chat_id])
    }
    pub fn delete_filter(&self, chat_id: i64, filter_id: i64) -> Res<bool> {
        let ok = self.exec("DELETE FROM filters WHERE chat_id=? AND id=?", args![chat_id, filter_id])? == 1;
        if ok {
            self.bump(chat_id);
        }
        Ok(ok)
    }
    pub fn add_link(&self, chat_id: i64, domain: &str, created_by: i64) -> Res<bool> {
        let ok = self.exec("INSERT OR IGNORE INTO link_allow(chat_id,domain,created_by) VALUES(?,?,?)", args![chat_id, domain, created_by])? == 1;
        if ok {
            self.bump(chat_id);
        }
        Ok(ok)
    }
    pub fn remove_link(&self, chat_id: i64, domain: &str) -> Res<bool> {
        let ok = self.exec("DELETE FROM link_allow WHERE chat_id=? AND domain=?", args![chat_id, domain])? == 1;
        if ok {
            self.bump(chat_id);
        }
        Ok(ok)
    }
    pub fn link_allow_set(&self, chat_id: i64) -> Res<HashSet<String>> {
        Ok(self.rows("SELECT domain FROM link_allow WHERE chat_id=?", args![chat_id])?.iter().filter_map(|r| r["domain"].as_str().map(String::from)).collect())
    }

    // ---------- ИИ-модерация ----------
    pub fn add_aimod_event(&self, chat_id: i64, user_id: i64, message_id: Option<i64>, category: &str, confidence: i64, action: &str, text: &str, ts: i64) -> Res<i64> {
        let t: String = text.chars().take(300).collect();
        self.insert(
            "INSERT INTO aimod_events(chat_id,user_id,message_id,category,confidence,action,text,status,ts) VALUES(?,?,?,?,?,?,?,'open',?)",
            args![chat_id, user_id, message_id, category, confidence, action, t, ts],
        )
    }
    pub fn get_aimod_event(&self, id: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM aimod_events WHERE id=?", args![id])
    }
    pub fn mark_aimod_false(&self, id: i64, by: i64) -> Res<bool> {
        Ok(self.exec("UPDATE aimod_events SET status='false', handled_by=? WHERE id=? AND status='open'", args![by, id])? == 1)
    }
    pub fn aimod_stats(&self, chat_id: i64, since: i64) -> Res<(i64, i64)> {
        let r = self.row("SELECT COUNT(*) AS n, COALESCE(SUM(CASE WHEN status='false' THEN 1 ELSE 0 END), 0) AS f FROM aimod_events WHERE chat_id=? AND ts>=?", args![chat_id, since])?.unwrap();
        Ok((r["n"].as_i64().unwrap_or(0), r["f"].as_i64().unwrap_or(0)))
    }

    // ---------- жалобы ----------
    pub fn add_report(&self, chat_id: i64, reporter: i64, target: i64, message_id: Option<i64>, text: &str, reason: &str, ts: i64) -> Res<i64> {
        self.insert(
            "INSERT INTO reports(chat_id,reporter,target,message_id,text,reason,status,ts) VALUES(?,?,?,?,?,?,'open',?)",
            args![chat_id, reporter, target, message_id, text, reason, ts],
        )
    }
    pub fn get_report(&self, id: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM reports WHERE id=?", args![id])
    }
    pub fn report_exists(&self, chat_id: i64, message_id: Option<i64>) -> Res<bool> {
        match message_id {
            None => Ok(false),
            Some(m) => self.exists("SELECT 1 FROM reports WHERE chat_id=? AND message_id=?", args![chat_id, m]),
        }
    }
    pub fn claim_report(&self, id: i64, status: &str, by: i64) -> Res<bool> {
        Ok(self.exec("UPDATE reports SET status=?, handled_by=? WHERE id=? AND status='open'", args![status, by, id])? == 1)
    }
    pub fn reopen_report(&self, id: i64) -> Res<()> {
        self.exec("UPDATE reports SET status='open', handled_by=NULL WHERE id=?", args![id])?;
        Ok(())
    }
    pub fn list_reports(&self, chat_id: i64, limit: i64) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM reports WHERE chat_id=? ORDER BY id DESC LIMIT ?", args![chat_id, limit])
    }
    pub fn count_rejected_reports(&self, chat_id: i64, reporter: i64) -> Res<i64> {
        self.scalar_i("SELECT COUNT(*) FROM reports WHERE chat_id=? AND reporter=? AND status='dismissed'", args![chat_id, reporter])
    }
    pub fn count_reports_against(&self, chat_id: i64, target: i64) -> Res<(i64, i64)> {
        let r = self.row("SELECT COUNT(*) AS n, COALESCE(SUM(CASE WHEN status='open' THEN 1 ELSE 0 END), 0) AS o FROM reports WHERE chat_id=? AND target=?", args![chat_id, target])?.unwrap();
        Ok((r["n"].as_i64().unwrap_or(0), r["o"].as_i64().unwrap_or(0)))
    }
    pub fn count_mod_history(&self, chat_id: i64, target_id: i64) -> Res<i64> {
        self.scalar_i("SELECT COUNT(*) FROM mod_log WHERE chat_id=? AND target_id=?", args![chat_id, target_id])
    }
    pub fn list_mod_history(&self, chat_id: i64, target_id: i64, limit: i64, offset: i64) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM mod_log WHERE chat_id=? AND target_id=? ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?", args![chat_id, target_id, limit, offset])
    }

    // ---------- ранги, доступ, состояния ----------
    pub fn get_rank(&self, chat_id: i64, user_id: i64) -> Res<i64> {
        if let Some((t, v)) = self.rank_cache.lock().unwrap().get(&(chat_id, user_id)) {
            if self.clock() - t < HOT_TTL {
                return Ok(*v);
            }
        }
        let v = self.row("SELECT rank FROM ranks WHERE chat_id=? AND user_id=?", args![chat_id, user_id])?.and_then(|r| r["rank"].as_i64()).unwrap_or(0);
        let mut c = self.rank_cache.lock().unwrap();
        if c.len() > 20000 {
            c.clear();
        }
        c.insert((chat_id, user_id), (self.clock(), v));
        Ok(v)
    }
    pub fn set_rank(&self, chat_id: i64, user_id: i64, rank: i64, by_user: i64, ts: i64) -> Res<()> {
        self.rank_cache.lock().unwrap().remove(&(chat_id, user_id));
        if rank <= 0 {
            self.exec("DELETE FROM ranks WHERE chat_id=? AND user_id=?", args![chat_id, user_id])?;
        } else {
            self.exec(
                "INSERT INTO ranks VALUES(?,?,?,?,?) ON CONFLICT(chat_id,user_id) DO UPDATE SET rank=excluded.rank, by_user=excluded.by_user, ts=excluded.ts",
                args![chat_id, user_id, rank, by_user, ts],
            )?;
        }
        Ok(())
    }
    pub fn list_ranks(&self, chat_id: i64) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM ranks WHERE chat_id=? ORDER BY rank DESC, ts", args![chat_id])
    }
    pub fn get_access(&self, chat_id: i64) -> Res<HashMap<String, i64>> {
        Ok(self.rows("SELECT command, min_rank FROM cmd_access WHERE chat_id=?", args![chat_id])?.iter().map(|r| (r["command"].as_str().unwrap().to_string(), r["min_rank"].as_i64().unwrap_or(0))).collect())
    }
    pub fn set_access(&self, chat_id: i64, command: &str, min_rank: i64) -> Res<()> {
        self.exec("INSERT INTO cmd_access VALUES(?,?,?) ON CONFLICT(chat_id,command) DO UPDATE SET min_rank=excluded.min_rank", args![chat_id, command, min_rank])?;
        Ok(())
    }
    pub fn reset_access(&self, chat_id: i64) -> Res<()> {
        self.exec("DELETE FROM cmd_access WHERE chat_id=?", args![chat_id])?;
        Ok(())
    }
    pub fn set_state(&self, chat_id: i64, user_id: i64, kind: &str, until: Option<i64>, reason: &str, actor: i64, ts: i64) -> Res<()> {
        self.exec(
            "INSERT INTO mod_state VALUES(?,?,?,?,?,?,?) ON CONFLICT(chat_id,user_id,kind) DO UPDATE SET until=excluded.until, reason=excluded.reason, actor=excluded.actor, ts=excluded.ts",
            args![chat_id, user_id, kind, until, reason, actor, ts],
        )?;
        Ok(())
    }
    pub fn clear_state(&self, chat_id: i64, user_id: i64, kind: &str) -> Res<()> {
        self.exec("DELETE FROM mod_state WHERE chat_id=? AND user_id=? AND kind=?", args![chat_id, user_id, kind])?;
        Ok(())
    }
    pub fn last_mod_action(&self, chat_id: i64, names: &[&str], target_id: Option<i64>) -> Res<Option<Row>> {
        let marks = vec!["?"; names.len()].join(",");
        let mut sql = format!("SELECT * FROM mod_log WHERE chat_id=? AND action IN ({marks})");
        let mut a = args![chat_id];
        a.extend(names.iter().map(|n| Param::param(n.to_string())));
        if let Some(t) = target_id {
            sql += " AND target_id=?";
            a.push(Param::param(t));
        }
        self.row(&(sql + " ORDER BY id DESC LIMIT 1"), a)
    }
    pub fn list_state(&self, chat_id: i64, kind: &str, now: i64) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM mod_state WHERE chat_id=? AND kind=? AND (until IS NULL OR until>?) ORDER BY ts DESC", args![chat_id, kind, now])
    }
    pub fn get_state(&self, chat_id: i64, user_id: i64, kind: &str, now: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM mod_state WHERE chat_id=? AND user_id=? AND kind=? AND (until IS NULL OR until>?)", args![chat_id, user_id, kind, now])
    }
    pub fn list_warns(&self, chat_id: i64, now: Option<i64>) -> Res<Vec<(i64, i64)>> {
        let now = now.unwrap_or_else(now_ts);
        Ok(self
            .rows("SELECT user_id, COUNT(*) AS n FROM warn_events WHERE chat_id=? AND (expires_at IS NULL OR expires_at>?) GROUP BY user_id ORDER BY n DESC, user_id", args![chat_id, now])?
            .iter()
            .map(|r| (r["user_id"].as_i64().unwrap(), r["n"].as_i64().unwrap()))
            .collect())
    }
    pub fn members_info(&self, chat_id: i64) -> Res<Vec<Row>> {
        self.rows("SELECT user_id, first_seen, last_seen, joined_at FROM chat_members WHERE chat_id=? AND active=1", args![chat_id])
    }
    pub fn last_messages(&self, chat_id: i64) -> Res<HashMap<i64, i64>> {
        self.flush_messages()?;
        Ok(self
            .rows(
                "SELECT user_id, MAX(t) AS t FROM (SELECT user_id, MAX(hour_ts) AS t FROM msg_hourly WHERE chat_id=? GROUP BY user_id UNION ALL \
                 SELECT user_id, MAX(day_ts) AS t FROM msg_daily WHERE chat_id=? GROUP BY user_id) x GROUP BY user_id",
                args![chat_id, chat_id],
            )?
            .iter()
            .map(|r| (r["user_id"].as_i64().unwrap(), r["t"].as_i64().unwrap_or(0)))
            .collect())
    }
    pub fn writers(&self, chat_id: i64) -> Res<HashSet<i64>> {
        self.flush_messages()?;
        Ok(self
            .rows("SELECT DISTINCT user_id FROM msg_hourly WHERE chat_id=? UNION SELECT DISTINCT user_id FROM msg_daily WHERE chat_id=?", args![chat_id, chat_id])?
            .iter()
            .filter_map(|r| r["user_id"].as_i64())
            .collect())
    }

    // ---------- тексты, профили, репутация, награды, заметки, таймеры ----------
    pub fn get_text(&self, chat_id: i64, key: &str) -> Res<Option<String>> {
        Ok(self.row("SELECT text FROM chat_texts WHERE chat_id=? AND key=?", args![chat_id, key])?.and_then(|r| r["text"].as_str().map(String::from)))
    }
    pub fn set_text(&self, chat_id: i64, key: &str, text: Option<&str>) -> Res<()> {
        match text {
            None => self.exec("DELETE FROM chat_texts WHERE chat_id=? AND key=?", args![chat_id, key])?,
            Some(t) => self.exec("INSERT INTO chat_texts VALUES(?,?,?) ON CONFLICT(chat_id,key) DO UPDATE SET text=excluded.text", args![chat_id, key, t])?,
        };
        Ok(())
    }
    pub fn get_profile(&self, chat_id: i64, user_id: i64) -> Res<Row> {
        Ok(self.row("SELECT * FROM profiles WHERE chat_id=? AND user_id=?", args![chat_id, user_id])?.unwrap_or_else(|| {
            let mut m = Row::new();
            for k in ["nick", "title", "motto", "about"] {
                m.insert(k.into(), Value::Null);
            }
            m
        }))
    }
    pub fn set_profile_field(&self, chat_id: i64, user_id: i64, field: &str, value: Option<&str>) -> Result<(), String> {
        if !["nick", "title", "motto", "about"].contains(&field) {
            return Err(format!("KeyError: {field}"));
        }
        let e = |e: rusqlite::Error| e.to_string();
        self.exec("INSERT OR IGNORE INTO profiles(chat_id,user_id) VALUES(?,?)", args![chat_id, user_id]).map_err(e)?;
        self.exec(&format!("UPDATE profiles SET {field}=? WHERE chat_id=? AND user_id=?"), args![value.map(String::from), chat_id, user_id]).map_err(e)?;
        Ok(())
    }
    pub fn get_rep(&self, chat_id: i64, user_id: i64) -> Res<i64> {
        self.scalar_i("SELECT score FROM reputation WHERE chat_id=? AND user_id=?", args![chat_id, user_id])
    }
    /// Новая репутация или None, если этот человек уже менял её цели в последний `cooldown`.
    pub fn change_rep(&self, chat_id: i64, from_user: i64, to_user: i64, delta: i64, now: i64, cooldown: i64) -> Res<Option<i64>> {
        let n = self.exec(
            "INSERT INTO rep_log SELECT CAST(? AS BIGINT),CAST(? AS BIGINT),CAST(? AS BIGINT),CAST(? AS BIGINT) WHERE NOT EXISTS (SELECT 1 FROM rep_log WHERE chat_id=? AND from_user=? AND to_user=? AND ts>?)",
            args![chat_id, from_user, to_user, now, chat_id, from_user, to_user, now - cooldown],
        )?;
        if n != 1 {
            return Ok(None);
        }
        self.exec("INSERT INTO reputation VALUES(?,?,?) ON CONFLICT(chat_id,user_id) DO UPDATE SET score=reputation.score+?", args![chat_id, to_user, delta, delta])?;
        Ok(Some(self.get_rep(chat_id, to_user)?))
    }
    pub fn top_rep(&self, chat_id: i64, limit: i64) -> Res<Vec<(i64, i64)>> {
        Ok(self.rows("SELECT user_id, score FROM reputation WHERE chat_id=? AND score<>0 ORDER BY score DESC, user_id LIMIT ?", args![chat_id, limit])?.iter().map(|r| (r["user_id"].as_i64().unwrap(), r["score"].as_i64().unwrap())).collect())
    }
    pub fn add_award(&self, chat_id: i64, user_id: i64, degree: i64, by_user: i64, ts: i64) -> Res<()> {
        self.exec("INSERT INTO awards(chat_id,user_id,degree,by_user,ts) VALUES(?,?,?,?,?)", args![chat_id, user_id, degree, by_user, ts])?;
        Ok(())
    }
    pub fn list_awards(&self, chat_id: i64, user_id: i64) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM awards WHERE chat_id=? AND user_id=? ORDER BY id", args![chat_id, user_id])
    }
    pub fn remove_last_award(&self, chat_id: i64, user_id: i64) -> Res<bool> {
        Ok(self.exec("DELETE FROM awards WHERE id=(SELECT MAX(id) FROM awards WHERE chat_id=? AND user_id=?)", args![chat_id, user_id])? == 1)
    }
    pub fn add_note(&self, chat_id: i64, user_id: i64, kind: &str, text: &str, ts: i64, limit: i64) -> Res<bool> {
        if self.scalar_i("SELECT COUNT(*) FROM notes WHERE chat_id=? AND user_id=? AND kind=?", args![chat_id, user_id, kind])? >= limit {
            return Ok(false);
        }
        self.exec("INSERT INTO notes(chat_id,user_id,kind,text,ts) VALUES(?,?,?,?,?)", args![chat_id, user_id, kind, text, ts])?;
        Ok(true)
    }
    pub fn list_notes(&self, chat_id: i64, user_id: i64, kind: &str) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM notes WHERE chat_id=? AND user_id=? AND kind=? ORDER BY id", args![chat_id, user_id, kind])
    }
    pub fn delete_note(&self, chat_id: i64, user_id: i64, kind: &str, note_id: i64) -> Res<bool> {
        Ok(self.exec("DELETE FROM notes WHERE id=? AND chat_id=? AND user_id=? AND kind=?", args![note_id, chat_id, user_id, kind])? == 1)
    }
    pub fn add_timer(&self, chat_id: i64, user_id: i64, due: i64, text: &str, limit: i64) -> Res<Option<i64>> {
        if self.scalar_i("SELECT COUNT(*) FROM timers WHERE chat_id=? AND user_id=?", args![chat_id, user_id])? >= limit {
            return Ok(None);
        }
        Ok(Some(self.insert("INSERT INTO timers(chat_id,user_id,due,text) VALUES(?,?,?,?)", args![chat_id, user_id, due, text])?))
    }
    pub fn list_timers(&self, chat_id: i64, user_id: i64) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM timers WHERE chat_id=? AND user_id=? ORDER BY due", args![chat_id, user_id])
    }
    pub fn delete_timer(&self, timer_id: i64, user_id: i64) -> Res<bool> {
        Ok(self.exec("DELETE FROM timers WHERE id=? AND user_id=?", args![timer_id, user_id])? == 1)
    }
    pub fn due_timers(&self, now: i64) -> Res<Vec<Row>> {
        self.rows("SELECT * FROM timers WHERE due<=? ORDER BY due", args![now])
    }
    pub fn pop_timer(&self, timer_id: i64) -> Res<bool> {
        Ok(self.exec("DELETE FROM timers WHERE id=?", args![timer_id])? == 1)
    }
    pub fn oldest_members(&self, chat_id: i64, limit: i64) -> Res<Vec<Row>> {
        self.rows("SELECT user_id, first_seen FROM chat_members WHERE chat_id=? AND active=1 ORDER BY first_seen LIMIT ?", args![chat_id, limit])
    }
    pub fn newest_members(&self, chat_id: i64, limit: i64) -> Res<Vec<Row>> {
        self.rows("SELECT user_id, COALESCE(joined_at, first_seen) AS t FROM chat_members WHERE chat_id=? AND active=1 ORDER BY t DESC LIMIT ?", args![chat_id, limit])
    }
    pub fn online_members(&self, chat_id: i64, now: i64, window: i64) -> Res<Vec<i64>> {
        Ok(self.rows("SELECT user_id FROM chat_members WHERE chat_id=? AND active=1 AND last_seen>? ORDER BY last_seen DESC", args![chat_id, now - window])?.iter().filter_map(|r| r["user_id"].as_i64()).collect())
    }

    // ---------- настройки ----------
    pub fn get_settings(&self, chat_id: i64) -> Res<Settings> {
        if let Some((t, s)) = self.settings_cache.lock().unwrap().get(&chat_id) {
            if self.clock() - t < SETTINGS_TTL {
                return Ok(s.clone());
            }
        }
        let mut out = default_settings();
        let mut stored = HashSet::new();
        for r in self.rows("SELECT key, value FROM settings WHERE chat_id=?", args![chat_id])? {
            let k = r["key"].as_str().unwrap_or("").to_string();
            let v = r["value"].as_str().unwrap_or("").to_string();
            stored.insert(k.clone());
            let is_int = matches!(out.get(&k), Some(Setting::Int(_)));
            // Python: int(value) для числовых, иначе строка; неизвестный ключ — строка
            let s = if is_int { Setting::Int(v.parse().unwrap_or(0)) } else { Setting::Str(v) };
            out.insert(k, s);
        }
        if !stored.contains("links_mode") && out.get("spam_links").map(|s| s.str()) == Some("0".into()) {
            out.insert("links_mode".into(), "off".into());
        }
        self.settings_cache.lock().unwrap().insert(chat_id, (self.clock(), out.clone()));
        Ok(out)
    }
    pub fn set_setting(&self, chat_id: i64, key: &str, value: impl ToString) -> Result<(), String> {
        if !default_settings().contains_key(key) {
            return Err(format!("KeyError: {key}"));
        }
        self.exec("INSERT INTO settings VALUES(?,?,?) ON CONFLICT(chat_id,key) DO UPDATE SET value=excluded.value", args![chat_id, key, value.to_string()]).map_err(|e| e.to_string())?;
        self.settings_cache.lock().unwrap().remove(&chat_id);
        Ok(())
    }

    // ---------- экономика ----------
    fn ensure_wallet(&self, chat_id: i64, user_id: i64) -> Res<()> {
        self.exec("INSERT OR IGNORE INTO wallets(chat_id,user_id,balance) VALUES(?,?,?)", args![chat_id, user_id, START_BALANCE])?;
        Ok(())
    }
    pub fn get_balance(&self, chat_id: i64, user_id: i64) -> Res<i64> {
        if let Some(r) = self.row("SELECT balance FROM wallets WHERE chat_id=? AND user_id=?", args![chat_id, user_id])? {
            return Ok(r["balance"].as_i64().unwrap_or(0));
        }
        self.ensure_wallet(chat_id, user_id)?;
        Ok(START_BALANCE)
    }
    pub fn change_balance(&self, chat_id: i64, user_id: i64, delta: i64) -> Res<i64> {
        self.ensure_wallet(chat_id, user_id)?;
        self.exec("UPDATE wallets SET balance=balance+? WHERE chat_id=? AND user_id=?", args![delta, chat_id, user_id])?;
        self.get_balance(chat_id, user_id)
    }
    pub fn transfer(&self, chat_id: i64, src: i64, dst: i64, amount: i64) -> Res<bool> {
        if amount < 1 || src == dst {
            return Ok(false);
        }
        self.ensure_wallet(chat_id, src)?;
        self.ensure_wallet(chat_id, dst)?;
        let mut c = self.conn.lock().unwrap();
        let tx = c.transaction()?;
        if tx.execute("UPDATE wallets SET balance=balance-? WHERE chat_id=? AND user_id=? AND balance>=?", rusqlite::params![amount, chat_id, src, amount])? != 1 {
            return Ok(false);
        }
        tx.execute("UPDATE wallets SET balance=balance+? WHERE chat_id=? AND user_id=?", rusqlite::params![amount, chat_id, dst])?;
        tx.commit()?;
        Ok(true)
    }
    pub fn try_spend(&self, chat_id: i64, user_id: i64, amount: i64) -> Res<bool> {
        self.ensure_wallet(chat_id, user_id)?;
        Ok(self.exec("UPDATE wallets SET balance=balance-? WHERE chat_id=? AND user_id=? AND balance>=?", args![amount, chat_id, user_id, amount])? == 1)
    }
    pub fn claim_bonus(&self, chat_id: i64, user_id: i64, now: i64) -> Res<Option<i64>> {
        self.ensure_wallet(chat_id, user_id)?;
        let day = now / DAY;
        let n = self.exec("UPDATE wallets SET balance=balance+?, last_bonus_day=? WHERE chat_id=? AND user_id=? AND last_bonus_day<?", args![BONUS_AMOUNT, day, chat_id, user_id, day])?;
        Ok((n == 1).then_some(BONUS_AMOUNT))
    }
    pub fn top_balances(&self, chat_id: i64, limit: i64) -> Res<Vec<(i64, i64)>> {
        Ok(self.rows("SELECT user_id, balance FROM wallets WHERE chat_id=? ORDER BY balance DESC LIMIT ?", args![chat_id, limit])?.iter().map(|r| (r["user_id"].as_i64().unwrap(), r["balance"].as_i64().unwrap())).collect())
    }
    pub fn add_win(&self, chat_id: i64, user_id: i64) -> Res<()> {
        self.ensure_wallet(chat_id, user_id)?;
        self.exec("UPDATE wallets SET wins=wins+1 WHERE chat_id=? AND user_id=?", args![chat_id, user_id])?;
        Ok(())
    }
    pub fn top_wins(&self, chat_id: i64, limit: i64) -> Res<Vec<(i64, i64)>> {
        Ok(self.rows("SELECT user_id, wins FROM wallets WHERE chat_id=? AND wins>0 ORDER BY wins DESC LIMIT ?", args![chat_id, limit])?.iter().map(|r| (r["user_id"].as_i64().unwrap(), r["wins"].as_i64().unwrap())).collect())
    }

    // ---------- Premium и платежи ----------
    pub fn premium_until(&self, user_id: i64) -> Res<Option<i64>> {
        Ok(self.row("SELECT until FROM premium WHERE user_id=?", args![user_id])?.and_then(|r| r["until"].as_i64()))
    }
    pub fn premium_grant(&self, user_id: i64, until: i64, now: i64) -> Res<()> {
        self.exec(
            "INSERT INTO premium(user_id,until,since,payments) VALUES(?,?,?,1) ON CONFLICT(user_id) DO UPDATE SET \
             until=CASE WHEN excluded.until>premium.until THEN excluded.until ELSE premium.until END, payments=premium.payments+1",
            args![user_id, until, now],
        )?;
        Ok(())
    }
    pub fn premium_revoke(&self, user_id: i64) -> Res<()> {
        self.exec("DELETE FROM premium WHERE user_id=?", args![user_id])?;
        Ok(())
    }
    pub fn payment_add(&self, user_id: i64, charge_id: &str, amount: i64, ts: i64, recurring: bool) -> Res<bool> {
        if self.exists("SELECT 1 FROM payments WHERE charge_id=?", args![charge_id])? {
            return Ok(false);
        }
        self.exec("INSERT INTO payments(user_id,charge_id,amount,ts,recurring) VALUES(?,?,?,?,?)", args![user_id, charge_id, amount, ts, recurring as i64])?;
        Ok(true)
    }
    pub fn payment_last(&self, user_id: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM payments WHERE user_id=? AND refunded=0 ORDER BY id DESC LIMIT 1", args![user_id])
    }
    pub fn payment_refunded(&self, pid: i64) -> Res<()> {
        self.exec("UPDATE payments SET refunded=1 WHERE id=?", args![pid])?;
        Ok(())
    }
    pub fn premium_stats(&self, now: i64) -> Res<(i64, i64)> {
        Ok((self.scalar_i("SELECT COUNT(*) FROM premium WHERE until>?", args![now])?, self.scalar_i("SELECT COALESCE(SUM(amount),0) FROM payments WHERE refunded=0", args![])?))
    }
    pub fn get_ai_model(&self, user_id: i64) -> Res<Option<String>> {
        Ok(self.row("SELECT ai_model FROM user_prefs WHERE user_id=?", args![user_id])?.and_then(|r| r["ai_model"].as_str().map(String::from)))
    }
    pub fn set_ai_model(&self, user_id: i64, model: Option<&str>) -> Res<()> {
        self.exec("INSERT INTO user_prefs(user_id,ai_model) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET ai_model=excluded.ai_model", args![user_id, model.map(String::from)])?;
        Ok(())
    }

    // ---------- промокоды ----------
    pub fn promo_create(&self, code: &str, days: i64, uses: i64, by: i64, now: i64) -> Res<bool> {
        Ok(self.exec("INSERT OR IGNORE INTO promo_codes(code,days,uses_left,created_by,created_at) VALUES(?,?,?,?,?)", args![code, days, uses, by, now])? == 1)
    }
    pub fn promo_delete(&self, code: &str) -> Res<bool> {
        Ok(self.exec("DELETE FROM promo_codes WHERE code=?", args![code])? == 1)
    }
    pub fn promo_list(&self) -> Res<Vec<Row>> {
        self.rows("SELECT c.code, c.days, c.uses_left, (SELECT COUNT(*) FROM promo_uses u WHERE u.code=c.code) AS used FROM promo_codes c ORDER BY c.created_at DESC LIMIT 50", args![])
    }
    /// ("ok", дней) | ("used", 0) | ("none", 0).
    pub fn promo_redeem(&self, code: &str, user_id: i64, now: i64) -> Res<(&'static str, i64)> {
        if self.exists("SELECT 1 FROM promo_uses WHERE code=? AND user_id=?", args![code, user_id])? {
            return Ok(("used", 0));
        }
        let Some(r) = self.row("SELECT days FROM promo_codes WHERE code=?", args![code])? else { return Ok(("none", 0)) };
        if self.exec("UPDATE promo_codes SET uses_left=uses_left-1 WHERE code=? AND uses_left>0", args![code])? != 1 {
            return Ok(("none", 0));
        }
        if self.exec("INSERT OR IGNORE INTO promo_uses(code,user_id,ts) VALUES(?,?,?)", args![code, user_id, now])? != 1 {
            self.exec("UPDATE promo_codes SET uses_left=uses_left+1 WHERE code=?", args![code])?;
            return Ok(("used", 0));
        }
        Ok(("ok", r["days"].as_i64().unwrap_or(0)))
    }

    // ---------- предложка ----------
    pub fn sugg_add(&self, chat_id: i64, user_id: i64, user_name: &str, text: &str, ts: i64) -> Res<i64> {
        self.insert("INSERT INTO suggestions(chat_id,user_id,user_name,text,ts,status) VALUES(?,?,?,?,?,'new')", args![chat_id, user_id, user_name, text, ts])
    }
    pub fn sugg_get(&self, sid: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM suggestions WHERE id=?", args![sid])
    }
    pub fn sugg_list(&self, chat_id: i64, only_new: bool) -> Res<Vec<Row>> {
        self.rows(&format!("SELECT * FROM suggestions WHERE chat_id=?{} ORDER BY id DESC LIMIT 500", if only_new { " AND status='new'" } else { "" }), args![chat_id])
    }
    /// (новых, всего).
    pub fn sugg_counts(&self, chat_id: i64) -> Res<(i64, i64)> {
        let r = self.row("SELECT COUNT(*) AS t, SUM(CASE WHEN status='new' THEN 1 ELSE 0 END) AS n FROM suggestions WHERE chat_id=?", args![chat_id])?.unwrap();
        Ok((r["n"].as_i64().unwrap_or(0), r["t"].as_i64().unwrap_or(0)))
    }
    pub fn sugg_decide(&self, sid: i64, status: &str, by: i64, ts: i64) -> Res<bool> {
        Ok(self.exec("UPDATE suggestions SET status=?, handled_by=?, handled_at=? WHERE id=? AND status='new'", args![status, by, ts, sid])? == 1)
    }
    pub fn sugg_delete(&self, sid: i64) -> Res<bool> {
        Ok(self.exec("DELETE FROM suggestions WHERE id=?", args![sid])? == 1)
    }

    // ---------- цитатник ----------
    pub fn quote_add(&self, chat_id: i64, author_id: i64, author_name: &str, text: &str, ts: i64, added_by: i64, added_at: i64, message_id: Option<i64>) -> Res<Option<i64>> {
        if let Some(m) = message_id {
            if self.exists("SELECT id FROM quotes WHERE chat_id=? AND message_id=?", args![chat_id, m])? {
                return Ok(None);
            }
        }
        Ok(Some(self.insert(
            "INSERT INTO quotes(chat_id,author_id,author_name,text,ts,added_by,added_at,message_id) VALUES(?,?,?,?,?,?,?,?)",
            args![chat_id, author_id, author_name, text, ts, added_by, added_at, message_id],
        )?))
    }
    pub fn quote_by_message(&self, chat_id: i64, message_id: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM quotes WHERE chat_id=? AND message_id=?", args![chat_id, message_id])
    }
    pub fn quote_get(&self, chat_id: i64, qid: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM quotes WHERE chat_id=? AND id=?", args![chat_id, qid])
    }
    pub fn quote_random(&self, chat_id: i64, author_id: Option<i64>) -> Res<Option<Row>> {
        let mut a = args![chat_id];
        let mut sql = "SELECT * FROM quotes WHERE chat_id=?".to_string();
        if let Some(x) = author_id {
            sql += " AND author_id=?";
            a.push(Param::param(x));
        }
        self.row(&(sql + " ORDER BY RANDOM() LIMIT 1"), a)
    }
    pub fn quote_list(&self, chat_id: i64, author_id: Option<i64>, limit: i64) -> Res<Vec<Row>> {
        let mut a = args![chat_id];
        let mut sql = "SELECT * FROM quotes WHERE chat_id=?".to_string();
        if let Some(x) = author_id {
            sql += " AND author_id=?";
            a.push(Param::param(x));
        }
        a.push(Param::param(limit));
        self.rows(&(sql + " ORDER BY id DESC LIMIT ?"), a)
    }
    pub fn quote_stats(&self, chat_id: i64, limit: i64) -> Res<(i64, Vec<(i64, String, i64)>)> {
        let total = self.scalar_i("SELECT COUNT(*) FROM quotes WHERE chat_id=?", args![chat_id])?;
        let rows = self.rows("SELECT author_id, MAX(author_name) AS name, COUNT(*) AS n FROM quotes WHERE chat_id=? GROUP BY author_id ORDER BY n DESC LIMIT ?", args![chat_id, limit])?;
        Ok((total, rows.iter().map(|r| (r["author_id"].as_i64().unwrap(), r["name"].as_str().unwrap_or("").into(), r["n"].as_i64().unwrap())).collect()))
    }
    pub fn quote_delete(&self, chat_id: i64, qid: i64) -> Res<bool> {
        Ok(self.exec("DELETE FROM quotes WHERE chat_id=? AND id=?", args![chat_id, qid])? == 1)
    }
    pub fn quote_set_card(&self, qid: i64, file_id: Option<&str>) -> Res<()> {
        self.exec("UPDATE quotes SET card=? WHERE id=?", args![file_id.map(String::from), qid])?;
        Ok(())
    }

    // ---------- мог-баттлы ----------
    pub fn mog_rating_get(&self, fp: &str, now: i64, max_age: i64) -> Res<Option<(i64, i64, i64)>> {
        let Some(r) = self.row("SELECT avatar, name, meaning, ts FROM mog_ratings WHERE fp=?", args![fp])? else { return Ok(None) };
        if now - r["ts"].as_i64().unwrap_or(0) > max_age {
            return Ok(None);
        }
        Ok(Some((r["avatar"].as_i64().unwrap_or(0), r["name"].as_i64().unwrap_or(0), r["meaning"].as_i64().unwrap_or(0))))
    }
    pub fn mog_rating_put(&self, fp: &str, avatar: i64, name: i64, meaning: i64, now: i64) -> Res<()> {
        self.exec(
            "INSERT INTO mog_ratings(fp,avatar,name,meaning,ts) VALUES(?,?,?,?,?) ON CONFLICT(fp) DO UPDATE SET avatar=excluded.avatar, name=excluded.name, meaning=excluded.meaning, ts=excluded.ts",
            args![fp, avatar, name, meaning, now],
        )?;
        Ok(())
    }
    pub fn mog_add(&self, chat_id: i64, winner: i64, loser: i64, ts: i64) -> Res<()> {
        self.exec("INSERT INTO mog_results(chat_id,winner,loser,ts) VALUES(?,?,?,?)", args![chat_id, winner, loser, ts])?;
        Ok(())
    }
    pub fn mog_top(&self, chat_id: i64, limit: i64) -> Res<Vec<(i64, i64, i64)>> {
        Ok(self
            .rows(
                "SELECT u, SUM(w) AS w, SUM(l) AS l FROM (SELECT winner AS u, 1 AS w, 0 AS l FROM mog_results WHERE chat_id=? UNION ALL \
                 SELECT loser AS u, 0 AS w, 1 AS l FROM mog_results WHERE chat_id=?) t GROUP BY u ORDER BY w DESC, l ASC LIMIT ?",
                args![chat_id, chat_id, limit],
            )?
            .iter()
            .map(|r| (r["u"].as_i64().unwrap(), r["w"].as_i64().unwrap(), r["l"].as_i64().unwrap()))
            .collect())
    }

    // ---------- конкурсы ----------
    pub const GW_FIELDS: [&'static str; 11] = ["step", "text", "photo", "winners", "publish_at", "ends_at", "status", "message_id", "result", "shown_count", "chat_id"];

    pub fn gw_new_draft(&self, chat_id: i64, creator: i64, now: i64) -> Res<i64> {
        self.exec("DELETE FROM giveaways WHERE creator=? AND status='draft'", args![creator])?;
        self.insert("INSERT INTO giveaways(chat_id,creator,step,status,created_at,winners) VALUES(?,?,'text','draft',?,1)", args![chat_id, creator, now])
    }
    pub fn gw_draft(&self, creator: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM giveaways WHERE creator=? AND status='draft' ORDER BY id DESC LIMIT 1", args![creator])
    }
    pub fn gw_get(&self, gid: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM giveaways WHERE id=?", args![gid])
    }
    pub fn gw_update(&self, gid: i64, fields: &[(&str, SqlValue)]) -> Result<(), String> {
        if let Some((k, _)) = fields.iter().find(|(k, _)| !Self::GW_FIELDS.contains(k)) {
            return Err(format!("KeyError: {k}"));
        }
        let sets = fields.iter().map(|(k, _)| format!("{k}=?")).collect::<Vec<_>>().join(",");
        let mut a: Vec<SqlValue> = fields.iter().map(|(_, v)| v.clone()).collect();
        a.push(Param::param(gid));
        self.exec(&format!("UPDATE giveaways SET {sets} WHERE id=?"), a).map_err(|e| e.to_string())?;
        Ok(())
    }
    pub fn gw_delete_draft(&self, creator: i64) -> Res<bool> {
        Ok(self.exec("DELETE FROM giveaways WHERE creator=? AND status='draft'", args![creator])? > 0)
    }
    pub fn gw_claim(&self, gid: i64, old: &str, new: &str) -> Res<bool> {
        Ok(self.exec("UPDATE giveaways SET status=? WHERE id=? AND status=?", args![new, gid, old])? == 1)
    }
    pub fn gw_due(&self, status: &str, column: &str, now: i64) -> Result<Vec<Row>, String> {
        if !["publish_at", "ends_at"].contains(&column) {
            return Err(format!("KeyError: {column}"));
        }
        self.rows(&format!("SELECT * FROM giveaways WHERE status=? AND {column}<=? ORDER BY {column}"), args![status, now]).map_err(|e| e.to_string())
    }
    pub fn gw_list(&self, chat_id: Option<i64>, statuses: &[&str]) -> Res<Vec<Row>> {
        let mut sql = format!("SELECT * FROM giveaways WHERE status IN ({})", vec!["?"; statuses.len()].join(","));
        let mut a: Vec<SqlValue> = statuses.iter().map(|s| Param::param(s.to_string())).collect();
        if let Some(c) = chat_id {
            sql += " AND chat_id=?";
            a.push(Param::param(c));
        }
        self.rows(&(sql + " ORDER BY id"), a)
    }
    pub fn gw_join(&self, gid: i64, user_id: i64, now: i64) -> Res<bool> {
        Ok(self.exec("INSERT OR IGNORE INTO giveaway_entries(gid,user_id,ts) VALUES(?,?,?)", args![gid, user_id, now])? == 1)
    }
    pub fn gw_joined(&self, gid: i64, user_id: i64) -> Res<bool> {
        self.exists("SELECT 1 FROM giveaway_entries WHERE gid=? AND user_id=?", args![gid, user_id])
    }
    pub fn gw_entries(&self, gid: i64) -> Res<Vec<i64>> {
        Ok(self.rows("SELECT user_id FROM giveaway_entries WHERE gid=? ORDER BY ts", args![gid])?.iter().filter_map(|r| r["user_id"].as_i64()).collect())
    }
    pub fn gw_count(&self, gid: i64) -> Res<i64> {
        self.scalar_i("SELECT COUNT(*) FROM giveaway_entries WHERE gid=?", args![gid])
    }
    pub fn gw_check_get(&self, gid: i64, user_id: i64) -> Res<Option<Row>> {
        self.row("SELECT * FROM giveaway_checks WHERE gid=? AND user_id=?", args![gid, user_id])
    }
    pub fn gw_check_new(&self, gid: i64, user_id: i64, answer: &str, expires_at: i64) -> Res<()> {
        self.exec("DELETE FROM giveaway_checks WHERE gid=? AND user_id=?", args![gid, user_id])?;
        self.exec("INSERT INTO giveaway_checks(gid,user_id,answer,attempts,expires_at) VALUES(?,?,?,0,?)", args![gid, user_id, answer, expires_at])?;
        Ok(())
    }
    pub fn gw_check_attempt(&self, gid: i64, user_id: i64, limit: i64) -> Res<bool> {
        Ok(self.exec("UPDATE giveaway_checks SET attempts=attempts+1 WHERE gid=? AND user_id=? AND attempts<?", args![gid, user_id, limit])? == 1)
    }
    pub fn gw_check_done(&self, gid: i64, user_id: i64) -> Res<()> {
        self.exec("DELETE FROM giveaway_checks WHERE gid=? AND user_id=?", args![gid, user_id])?;
        Ok(())
    }
    pub fn gw_prune(&self, now: i64) -> Res<()> {
        self.exec("DELETE FROM giveaway_checks WHERE expires_at<?", args![now - 86400])?;
        self.exec("DELETE FROM giveaways WHERE status='draft' AND created_at<?", args![now - 2 * 86400])?;
        Ok(())
    }

    // ---------- капча ----------
    pub fn create_captcha(&self, token: &str, chat_id: i64, user_id: i64, answer: &Value, expires_at: i64, message_id: i64, kind: &str, level: i64) -> Res<()> {
        self.exec(
            "INSERT INTO captchas(token,chat_id,user_id,answer,expires_at,message_id,kind,level) VALUES(?,?,?,?,?,?,?,?)",
            args![token, chat_id, user_id, answer.to_string(), expires_at, message_id, kind, level],
        )?;
        Ok(())
    }
    fn captcha(mut r: Row) -> Row {
        if let Some(Value::String(s)) = r.get("answer") {
            if let Ok(v) = serde_json::from_str::<Value>(s) {
                r.insert("answer".into(), v);
            }
        }
        r
    }
    pub fn get_captcha(&self, token: &str) -> Res<Option<Row>> {
        Ok(self.row("SELECT * FROM captchas WHERE token=?", args![token])?.map(Self::captcha))
    }
    pub fn get_captcha_for_user(&self, chat_id: i64, user_id: i64) -> Res<Option<Row>> {
        Ok(self.row("SELECT * FROM captchas WHERE chat_id=? AND user_id=?", args![chat_id, user_id])?.map(Self::captcha))
    }
    pub fn take_captcha_attempt(&self, token: &str, limit: i64) -> Res<bool> {
        Ok(self.exec("UPDATE captchas SET attempts=COALESCE(attempts,0)+1 WHERE token=? AND COALESCE(attempts,0)<?", args![token, limit])? == 1)
    }
    pub fn consume_captcha(&self, token: &str) -> Res<bool> {
        Ok(self.exec("DELETE FROM captchas WHERE token=?", args![token])? == 1)
    }
    pub fn expired_captchas(&self, now: i64) -> Res<Vec<Row>> {
        Ok(self.rows("SELECT * FROM captchas WHERE expires_at<=?", args![now])?.into_iter().map(Self::captcha).collect())
    }

    // ---------- дуэли ----------
    pub fn save_duel(&self, duel: &Value) -> Res<()> {
        self.exec(
            "INSERT INTO duels VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data, expires_at=excluded.expires_at",
            args![duel["id"].as_str().unwrap_or("").to_string(), duel["chat_id"].as_i64().unwrap_or(0), duel.to_string(), duel["expires_at"].as_i64().unwrap_or(0)],
        )?;
        Ok(())
    }
    fn duels(rows: Vec<Row>) -> Vec<Value> {
        rows.iter().filter_map(|r| serde_json::from_str(r["data"].as_str()?).ok()).collect()
    }
    pub fn get_duel(&self, id: &str) -> Res<Option<Value>> {
        Ok(Self::duels(self.rows("SELECT data FROM duels WHERE id=?", args![id])?).into_iter().next())
    }
    pub fn delete_duel(&self, id: &str) -> Res<bool> {
        Ok(self.exec("DELETE FROM duels WHERE id=?", args![id])? == 1)
    }
    pub fn chat_duels(&self, chat_id: i64) -> Res<Vec<Value>> {
        Ok(Self::duels(self.rows("SELECT data FROM duels WHERE chat_id=?", args![chat_id])?))
    }
    pub fn expired_duels(&self, now: i64) -> Res<Vec<Value>> {
        Ok(Self::duels(self.rows("SELECT data FROM duels WHERE expires_at<=?", args![now])?))
    }
}
