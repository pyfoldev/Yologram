//! Чистая логика антиспама (без Telegram). Порт `bot/spam.py`.
use regex::Regex;
use std::collections::{HashMap, VecDeque};
use std::sync::LazyLock;

pub const DUP_LIMIT: i64 = 3;
pub const DUP_WINDOW: f64 = 60.0;
pub const DUP_MIN_LEN: usize = 15;

#[derive(Debug, Clone, Default)]
pub struct MsgInfo {
    pub text: String,
    pub links: u32,
    pub mentions: u32,
    pub forwarded: bool,
    pub group_id: Option<String>, // media_group_id: альбом считаем одним сообщением
}

/// Нужные антиспаму настройки чата (ключи `spam_*`, `links_mode` из `DEFAULT_SETTINGS`).
#[derive(Debug, Clone)]
pub struct SpamSettings {
    pub spam_mentions: u32,
    pub links_mode: String, // off | newbies | all
    pub spam_forward: bool,
    pub spam_flood_sec: f64,
    pub spam_flood_n: usize,
    pub spam_dup: bool,
}

impl Default for SpamSettings {
    fn default() -> Self {
        Self { spam_mentions: 5, links_mode: "newbies".into(), spam_forward: false, spam_flood_sec: 10.0, spam_flood_n: 5, spam_dup: true }
    }
}

static WS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());

fn norm(text: &str) -> String {
    WS.replace_all(text, " ").trim().to_lowercase()
}

type Key = (i64, i64);

#[derive(Default)]
pub struct SpamGuard {
    times: HashMap<Key, VecDeque<f64>>,
    last: HashMap<Key, (String, i64, f64)>,
    album: HashMap<Key, String>,
}

impl SpamGuard {
    pub fn new() -> Self {
        Self::default()
    }

    /// Забыть тех, кто давно молчит.
    pub fn prune(&mut self, now: f64, max_age: f64) {
        self.times.retain(|_, v| v.back().is_some_and(|t| now - t <= max_age));
        self.last.retain(|_, v| now - v.2 <= max_age);
        if self.album.len() > 5000 {
            self.album.clear();
        }
    }

    pub fn check(&mut self, chat_id: i64, user_id: i64, info: &MsgInfo, now: f64, s: &SpamSettings, newbie: bool) -> Option<&'static str> {
        let key = (chat_id, user_id);
        if info.mentions > s.spam_mentions {
            return Some("массовые упоминания");
        }
        if info.links > 0 {
            match s.links_mode.as_str() {
                "all" => return Some("ссылка не из белого списка"),
                "newbies" if newbie => return Some("ссылка от новичка"),
                _ => {}
            }
        }
        if newbie && s.spam_forward && info.forwarded {
            return Some("пересылка от новичка");
        }
        if let Some(g) = &info.group_id {
            if self.album.get(&key) == Some(g) {
                return None; // остальные фото того же альбома
            }
            self.album.insert(key, g.clone());
        }
        if info.forwarded {
            return None; // пачка пересланных — не флуд
        }

        let times = self.times.entry(key).or_default();
        times.push_back(now);
        while times.front().is_some_and(|t| now - t >= s.spam_flood_sec) {
            times.pop_front();
        }
        if times.len() >= s.spam_flood_n {
            times.clear();
            return Some("флуд");
        }

        let text = norm(&info.text);
        if s.spam_dup && text.chars().count() >= DUP_MIN_LEN {
            let count = match self.last.get(&key) {
                Some(p) if p.0 == text && now - p.2 <= DUP_WINDOW => p.1 + 1,
                _ => 1,
            };
            self.last.insert(key, (text, count, now));
            if count >= DUP_LIMIT {
                self.last.remove(&key);
                return Some("повторы");
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn info(t: &str) -> MsgInfo {
        MsgInfo { text: t.into(), ..Default::default() }
    }

    #[test]
    fn flood_threshold_and_reset() {
        let mut g = SpamGuard::new();
        let s = SpamSettings { spam_flood_n: 3, spam_flood_sec: 10.0, ..Default::default() };
        assert_eq!(g.check(1, 2, &info("a0"), 100.0, &s, false), None);
        assert_eq!(g.check(1, 2, &info("a1"), 101.0, &s, false), None);
        assert_eq!(g.check(1, 2, &info("a2"), 102.0, &s, false), Some("флуд"));
        assert_eq!(g.check(1, 2, &info("b0"), 103.0, &s, false), None);
        assert_eq!(g.check(1, 3, &info("m"), 100.0, &s, false), None); // другой пользователь
        let mut g2 = SpamGuard::new();
        g2.check(1, 2, &info("x"), 100.0, &s, false);
        g2.check(1, 2, &info("y"), 101.0, &s, false);
        assert_eq!(g2.check(1, 2, &info("z"), 200.0, &s, false), None); // вне окна
    }

    #[test]
    fn duplicates() {
        let mut g = SpamGuard::new();
        let s = SpamSettings { spam_flood_n: 50, ..Default::default() };
        assert_eq!(g.check(1, 2, &info("Купи Подписку на канал"), 100.0, &s, false), None);
        assert_eq!(g.check(1, 2, &info("купи подписку на канал "), 101.0, &s, false), None);
        assert_eq!(g.check(1, 2, &info("КУПИ ПОДПИСКУ НА КАНАЛ"), 102.0, &s, false), Some("повторы"));
        let off = SpamSettings { spam_dup: false, spam_flood_n: 50, ..Default::default() };
        let mut g2 = SpamGuard::new();
        for i in 0..5 {
            assert_eq!(g2.check(1, 2, &info("то же самое сообщение"), 100.0 + i as f64, &off, false), None);
        }
    }

    #[test]
    fn links_mentions_forward_album() {
        let mut g = SpamGuard::new();
        let s = SpamSettings::default();
        let link = MsgInfo { links: 1, ..info("x") };
        assert_eq!(g.check(1, 2, &link, 1.0, &s, true), Some("ссылка от новичка"));
        assert_eq!(g.check(1, 2, &link, 1.0, &s, false), None);
        let all = SpamSettings { links_mode: "all".into(), ..Default::default() };
        assert_eq!(g.check(1, 2, &link, 1.0, &all, false), Some("ссылка не из белого списка"));
        assert_eq!(g.check(1, 2, &MsgInfo { mentions: 6, ..info("x") }, 1.0, &s, false), Some("массовые упоминания"));
        let fwd = SpamSettings { spam_forward: true, ..Default::default() };
        assert_eq!(g.check(1, 2, &MsgInfo { forwarded: true, ..info("x") }, 1.0, &fwd, true), Some("пересылка от новичка"));
        let alb = MsgInfo { group_id: Some("g".into()), ..info("p") };
        let burst = SpamSettings { spam_flood_n: 2, ..Default::default() };
        assert_eq!(g.check(7, 7, &alb, 1.0, &burst, false), None);
        for _ in 0..5 {
            assert_eq!(g.check(7, 7, &alb, 1.0, &burst, false), None); // альбом = одно сообщение
        }
    }
}
