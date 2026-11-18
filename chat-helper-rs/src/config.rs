//! Конфигурация из окружения и `.env`. Порт `bot/config.py` (те же переменные).
use std::collections::HashSet;

#[derive(Debug, Clone)]
pub struct Config {
    pub bot_token: String,
    pub webapp_url: String,
    pub owner_id: i64,
    pub owner_ids: HashSet<i64>,
    pub webhook_secret: String,
    pub webhook_url: String,
    pub db_path: String,
    pub mode: String,
    pub port: u16,
    pub river_api_key: String,
    pub river_base_url: String,
    pub river_model: String,
    pub river_vision_model: String,
    pub mistral_api_key: String,
    pub mistral_base_url: String,
    pub mistral_stt_model: String,
    pub update_workers: usize,
    pub cf_account_id: String,
    pub cf_api_token: String,
    pub cf_image_model: String,
    pub premium_price: i64,
}

/// OWNER_ID=123 или несколько через запятую / «;».
pub fn parse_owner_ids(raw: &str) -> Vec<i64> {
    raw.replace(';', ",")
        .split(',')
        .map(str::trim)
        .filter(|p| p.trim_start_matches('-').chars().all(|c| c.is_ascii_digit()) && !p.trim_start_matches('-').is_empty())
        .filter_map(|p| p.parse::<i64>().ok())
        .filter(|n| *n != 0)
        .collect()
}

/// Лёгкая загрузка `.env` (не перезаписывает уже заданные переменные).
fn load_dotenv(path: &str) {
    let Ok(text) = std::fs::read_to_string(path) else { return };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || !line.contains('=') {
            continue;
        }
        let (k, v) = line.split_once('=').unwrap();
        if std::env::var_os(k.trim()).is_none() {
            // SAFETY-free: set_var небезопасна только в многопоточном коде; вызывается до запуска рантайма
            #[allow(unused_unsafe)]
            unsafe {
                std::env::set_var(k.trim(), v.trim())
            };
        }
    }
}

fn env(k: &str, d: &str) -> String {
    std::env::var(k).unwrap_or_else(|_| d.to_string())
}
fn env_or(k: &str, d: &str) -> String {
    let v = env(k, d);
    if v.is_empty() { d.to_string() } else { v }
}
fn env_num<T: std::str::FromStr>(k: &str, d: T) -> T {
    std::env::var(k).ok().and_then(|v| v.trim().parse().ok()).unwrap_or(d)
}

pub fn load_config() -> Result<Config, String> {
    load_dotenv(".env");
    let token = env("BOT_TOKEN", "");
    if token.is_empty() {
        return Err("BOT_TOKEN не задан (см. .env.example)".into());
    }
    let owners = parse_owner_ids(&env("OWNER_ID", ""));
    Ok(Config {
        bot_token: token,
        webapp_url: env("WEBAPP_URL", "").trim_end_matches('/').into(),
        owner_id: owners.first().copied().unwrap_or(0),
        owner_ids: owners.into_iter().collect(),
        webhook_secret: env("WEBHOOK_SECRET", ""),
        webhook_url: env("WEBHOOK_URL", "").trim_end_matches('/').into(),
        db_path: env("DB_PATH", "data/bot.db"),
        mode: env("MODE", "polling"),
        port: env_num("PORT", 8080),
        river_api_key: env("RIVER_API_KEY", ""),
        river_base_url: env("RIVER_BASE_URL", "https://api.river.ai/v1").trim_end_matches('/').into(),
        river_model: env("RIVER_MODEL", "deepseek-ai/DeepSeek-V4.1-Flash"),
        river_vision_model: env("RIVER_VISION_MODEL", ""),
        mistral_api_key: env("MISTRAL_API_KEY", ""),
        mistral_base_url: env("MISTRAL_BASE_URL", "https://api.mistral.ai/v1").trim_end_matches('/').into(),
        mistral_stt_model: env("MISTRAL_STT_MODEL", "voxtral-mini-latest"),
        update_workers: env_num::<usize>("UPDATE_WORKERS", 64).max(1),
        cf_account_id: env("CF_ACCOUNT_ID", ""),
        cf_api_token: env("CF_API_TOKEN", ""),
        cf_image_model: env_or("CF_IMAGE_MODEL", "@cf/black-forest-labs/flux-1-schnell"),
        premium_price: env_num("PREMIUM_PRICE", 70),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn owners() {
        assert_eq!(parse_owner_ids("123, 456;789"), vec![123, 456, 789]);
        assert_eq!(parse_owner_ids("abc,0,-5, ,12x"), vec![-5]);
        assert!(parse_owner_ids("").is_empty());
    }
}
