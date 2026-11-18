//! Антирейд. Порт `bot/raid.py`.
use std::collections::{HashMap, VecDeque};

#[derive(Default)]
pub struct RaidGuard {
    joins: HashMap<i64, VecDeque<f64>>,
    until: HashMap<i64, f64>,
}

impl RaidGuard {
    pub fn until(&self, chat_id: i64) -> f64 {
        *self.until.get(&chat_id).unwrap_or(&0.0)
    }
    /// (режим_активен, сработал_только_что). `raid_sec`, `raid_n`, `raid_minutes` — настройки чата.
    pub fn register(&mut self, chat_id: i64, now: f64, raid_sec: f64, raid_n: usize, raid_minutes: f64) -> (bool, bool) {
        if self.until(chat_id) > now {
            return (true, false);
        }
        let joins = self.joins.entry(chat_id).or_default();
        joins.push_back(now);
        while joins.front().is_some_and(|t| now - t >= raid_sec) {
            joins.pop_front();
        }
        if joins.len() >= raid_n {
            joins.clear();
            self.until.insert(chat_id, now + raid_minutes * 60.0);
            return (true, true);
        }
        (false, false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn raid() {
        let mut g = RaidGuard::default();
        for i in 0..4 {
            assert_eq!(g.register(1, 100.0 + i as f64, 60.0, 5, 10.0), (false, false));
        }
        assert_eq!(g.register(1, 104.0, 60.0, 5, 10.0), (true, true));
        assert_eq!(g.register(1, 105.0, 60.0, 5, 10.0), (true, false));
        assert_eq!(g.register(1, 100.0 + 700.0, 60.0, 5, 10.0), (false, false));
    }
}
