//! Упоминания и разбор цели команды. Порт `bot/targets.py`.
use crate::db::Database;
use crate::fmt::escape;
use regex::Regex;
use std::sync::LazyLock;

static USERNAME_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[A-Za-z0-9_]{4,32}$").unwrap());

fn username(db: &Database, user_id: i64) -> Option<String> {
    let u = db.usernames.lock().unwrap().get(&user_id).cloned().flatten()?;
    USERNAME_RE.is_match(&u).then_some(u)
}

/// Кликабельное имя (по @нику, если известен: такая ссылка открывается всегда). Возвращает готовый HTML.
pub fn mention(db: &Database, user_id: i64, name: &str, maxlen: Option<usize>) -> String {
    let mut name = if name.is_empty() { format!("id{user_id}") } else { name.to_string() };
    if let Some(m) = maxlen {
        if name.chars().count() > m {
            name = name.chars().take(m.saturating_sub(1)).collect::<String>() + "…";
        }
    }
    let href = match username(db, user_id) {
        Some(u) => format!("https://t.me/{u}"),
        None => format!("tg://user?id={user_id}"),
    };
    format!("<a href=\"{href}\">{}</a>", escape(&name))
}

/// Для списков: имя-ссылка и рядом @ник.
pub fn listed(db: &Database, user_id: i64, name: &str, maxlen: Option<usize>) -> String {
    let m = mention(db, user_id, name, maxlen);
    match username(db, user_id) {
        Some(u) => format!("{m} (@{u})"),
        None => m,
    }
}

/// Позвать человека: @ник присылает уведомление всегда.
pub fn ping(db: &Database, user_id: i64, name: &str) -> String {
    match username(db, user_id) {
        Some(u) => format!("@{u}"),
        None => format!("<a href=\"tg://user?id={user_id}\">{}</a>", escape(&if name.is_empty() { format!("id{user_id}") } else { name.to_string() })),
    }
}

/// (id, имя) явной цели `token` (@ник / id) или автора ответа `reply`.
pub fn resolve_user(db: &Database, token: Option<&str>, reply: Option<(i64, String)>) -> Option<(i64, String)> {
    if let Some(t) = token {
        let uid = if t.starts_with('@') { db.find_user(t).ok().flatten()? } else { t.parse().ok()? };
        let name = db.user_name(uid).ok().flatten().unwrap_or_else(|| format!("id{uid}"));
        return Some((uid, name));
    }
    reply
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn mentions() {
        let db = Database::connect(":memory:").unwrap();
        assert_eq!(mention(&db, 5, "A<b>", None), "<a href=\"tg://user?id=5\">A&lt;b&gt;</a>");
        db.remember_user(5, Some("@sasha_k"), "Саша").unwrap();
        assert_eq!(listed(&db, 5, "Саша", None), "<a href=\"https://t.me/sasha_k\">Саша</a> (@sasha_k)");
        assert_eq!(ping(&db, 5, "x"), "@sasha_k");
        assert_eq!(resolve_user(&db, Some("@Sasha_K"), None), Some((5, "Саша".into())));
        assert_eq!(resolve_user(&db, Some("@nobody"), None), None);
        assert_eq!(resolve_user(&db, Some("777"), None), Some((777, "id777".into())));
    }
}
