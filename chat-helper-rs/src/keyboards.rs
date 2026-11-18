//! Клавиатуры. Порт `bot/keyboards.py`.
use teloxide::types::{InlineKeyboardButton as B, InlineKeyboardMarkup};

fn cb(text: &str, data: &str) -> B {
    B::callback(text.to_string(), data.to_string())
}

pub fn main_menu() -> InlineKeyboardMarkup {
    InlineKeyboardMarkup::new(vec![
        vec![cb("🛡 Модерация", "menu:mod"), cb("📊 Статистика", "menu:stats")],
        vec![cb("🎮 Игры и фан", "menu:games"), cb("🚫 Защита", "menu:protect")],
        vec![cb("🎭 РП и семья", "menu:rp"), cb("⚙️ Настройки", "menu:settings")],
        vec![cb("🎖 Ранги и профиль", "menu:ranks"), cb("❓ Помощь", "menu:help")],
    ])
}

pub fn section_keyboard() -> InlineKeyboardMarkup {
    InlineKeyboardMarkup::new(vec![vec![cb("⬅️ В меню", "menu:home")]])
}

pub fn stats_keyboard(active: &str) -> InlineKeyboardMarkup {
    let b = |label: &str, key: &str| cb(&format!("{}{label}", if key == active { "• " } else { "" }), &format!("stats:{key}"));
    InlineKeyboardMarkup::new(vec![vec![b("2 часа", "2"), b("День", "24"), b("Вся", "all")]])
}

pub fn undo_keyboard(action: &str, target_id: i64) -> InlineKeyboardMarkup {
    InlineKeyboardMarkup::new(vec![vec![cb("↩️ Отменить", &format!("undo:{action}:{target_id}"))]])
}
