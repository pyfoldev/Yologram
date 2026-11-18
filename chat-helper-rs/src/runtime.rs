//! Состояние работающего бота: блок-лист, глобальные флаги, остановка/перезапуск. Порт `bot/runtime.py`.
use crate::db::Database;
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::RwLock;
use std::time::Instant;
use tokio::sync::Notify;

pub struct Runtime {
    pub started_at: Instant,
    pub restart: AtomicBool,
    pub stop_requested: AtomicBool,
    notify: Notify,
    pub banned_users: RwLock<HashSet<i64>>,
    pub banned_chats: RwLock<HashSet<i64>>,
    pub left_chats: RwLock<HashSet<i64>>,
    pub flags: RwLock<HashMap<&'static str, bool>>,
    pub maintenance: AtomicBool,
}

impl Default for Runtime {
    fn default() -> Self {
        Self {
            started_at: Instant::now(),
            restart: AtomicBool::new(false),
            stop_requested: AtomicBool::new(false),
            notify: Notify::new(),
            banned_users: Default::default(),
            banned_chats: Default::default(),
            left_chats: Default::default(),
            flags: RwLock::new(["ai", "stt", "nsfw", "aimod"].into_iter().map(|k| (k, true)).collect()),
            maintenance: AtomicBool::new(false),
        }
    }
}

impl Runtime {
    pub fn flag(&self, name: &str) -> bool {
        *self.flags.read().unwrap().get(name).unwrap_or(&true)
    }
    pub fn apply_globals(&self, g: &HashMap<String, i64>) {
        let mut f = self.flags.write().unwrap();
        for (k, key) in [("ai", "g_ai"), ("stt", "g_stt"), ("nsfw", "g_nsfw"), ("aimod", "g_aimod")] {
            f.insert(k, g.get(key).copied().unwrap_or(1) != 0);
        }
        self.maintenance.store(g.get("maintenance").copied().unwrap_or(0) != 0, Ordering::SeqCst);
    }
    pub fn load(&self, db: &Database) -> rusqlite::Result<()> {
        let (u, c) = db.load_bans()?;
        *self.banned_users.write().unwrap() = u;
        *self.banned_chats.write().unwrap() = c;
        self.apply_globals(&db.get_global()?);
        Ok(())
    }
    pub fn request_stop(&self, restart: bool) {
        self.restart.store(restart, Ordering::SeqCst);
        self.stop_requested.store(true, Ordering::SeqCst);
        self.notify.notify_waiters();
        self.notify.notify_one();
    }
    pub async fn wait_stop(&self) {
        if !self.stop_requested.load(Ordering::SeqCst) {
            self.notify.notified().await;
        }
    }
}
