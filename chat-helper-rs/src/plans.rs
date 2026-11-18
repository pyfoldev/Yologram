//! Тарифы: бесплатный и Premium. Порт `bot/plans.py`.
use crate::db::{now_ts, Database};
use std::collections::HashSet;

pub const PREMIUM_PRICE: i64 = 70;
pub const PREMIUM_DAYS: i64 = 30;
pub const PREMIUM_PERIOD: i64 = PREMIUM_DAYS * 86400;

#[derive(Debug, PartialEq, Eq)]
pub struct Plan {
    pub name: &'static str,
    pub ai_daily: i64,
    pub ai_cooldown: i64,
    pub ai_max_tokens: i64,
    pub ai_reply_chars: i64,
    pub img_daily: i64,
    pub img_cooldown: i64,
    pub choose_model: bool,
    pub ai_private: bool,
}

pub static FREE: Plan = Plan { name: "Бесплатный", ai_daily: 25, ai_cooldown: 20, ai_max_tokens: 800, ai_reply_chars: 2000, img_daily: 5, img_cooldown: 45, choose_model: false, ai_private: false };
pub static PREMIUM: Plan = Plan { name: "Premium", ai_daily: 150, ai_cooldown: 0, ai_max_tokens: 1600, ai_reply_chars: 3500, img_daily: 30, img_cooldown: 0, choose_model: true, ai_private: true };

/// Владельцы бота — всегда Premium; остальные — по оплаченной подписке.
pub fn plan_for(db: &Database, user_id: i64, owner_ids: &HashSet<i64>, now: Option<i64>) -> &'static Plan {
    if owner_ids.contains(&user_id) {
        return &PREMIUM;
    }
    match db.premium_until(user_id).ok().flatten() {
        Some(u) if u > now.unwrap_or_else(now_ts) => &PREMIUM,
        _ => &FREE,
    }
}

pub fn benefits_text(price: i64) -> String {
    format!(
        "⭐ <b>Nori Premium</b> — {price} звёзд в месяц\n• ИИ без кулдауна (бесплатно — раз в {} с)\n• {} вопросов к ИИ в сутки вместо {}, ответы длиннее и подробнее\n• выбор модели ИИ: <code>модель</code>\n• ИИ в личке с ботом\n• {} картинок в сутки вместо {}, без ожидания: <code>нарисуй …</code>\nПодписка продлевается автоматически, отменить можно в любой момент в Telegram.",
        FREE.ai_cooldown, PREMIUM.ai_daily, FREE.ai_daily, PREMIUM.img_daily, FREE.img_daily
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn plans() {
        let db = Database::connect(":memory:").unwrap();
        let owners: HashSet<i64> = [1].into();
        assert_eq!(plan_for(&db, 1, &owners, Some(0)), &PREMIUM);
        assert_eq!(plan_for(&db, 2, &owners, Some(0)), &FREE);
        db.premium_grant(2, 100, 0).unwrap();
        assert_eq!(plan_for(&db, 2, &owners, Some(50)), &PREMIUM);
        assert_eq!(plan_for(&db, 2, &owners, Some(100)), &FREE);
        assert!(benefits_text(70).contains("70 звёзд"));
    }
}
