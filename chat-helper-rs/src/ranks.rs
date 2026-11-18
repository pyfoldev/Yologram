//! Ранги модераторов (1–5) и «доступ команд» (ДК). Порт `bot/ranks.py`.
use std::collections::HashMap;

pub const MAX_RANK: i64 = 5;
pub const MAX_ASSIGNABLE: i64 = 4;
pub const TG_ADMIN_RANK: i64 = 3;

pub const DEFAULT_ACCESS: [(&str, i64); 22] = [
    ("варн", 1), ("снять варн", 1), ("мут", 1), ("размут", 1), ("удалить", 1), ("фильтры", 3), ("ссылки", 3),
    ("история", 1), ("кик", 2), ("чистка", 2), ("закреп", 2), ("открепить", 2), ("бан", 3), ("разбан", 3),
    ("модер", 4), ("массовый кик", 4), ("настройки чата", 4), ("награды", 3), ("неактивные", 2),
    ("общий сбор", 2), ("конкурсы", 5), ("предложка", 5),
];

pub fn need_rank(access: &HashMap<String, i64>, command: &str) -> i64 {
    access.get(command).copied().or_else(|| DEFAULT_ACCESS.iter().find(|(k, _)| *k == command).map(|(_, v)| *v)).unwrap_or(3)
}

pub fn tg_rank(status: &str, can_restrict: bool) -> i64 {
    match status {
        "creator" => MAX_RANK,
        "administrator" if can_restrict => TG_ADMIN_RANK,
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ranks() {
        let mut a = HashMap::new();
        assert_eq!(need_rank(&a, "бан"), 3);
        assert_eq!(need_rank(&a, "неизвестная"), 3);
        a.insert("бан".into(), 1);
        assert_eq!(need_rank(&a, "бан"), 1);
        assert_eq!((tg_rank("creator", false), tg_rank("administrator", true), tg_rank("administrator", false)), (5, 3, 0));
    }
}
