//! Премиум-эмодзи. Порт `bot/emoji.py` + загрузка карты из `bot/emoji_inject.py`.
use std::collections::HashMap;
use std::sync::LazyLock;

pub const FALLBACK: [(&str, &str); 17] = [
    ("ban", "🔨"), ("mute", "🔇"), ("kick", "👢"), ("warn", "⚠️"), ("stats", "📊"), ("game", "🎮"), ("slots", "🎰"),
    ("shield", "🛡"), ("ok", "✅"), ("fail", "❌"), ("menu", "📋"), ("settings", "⚙️"), ("coin", "🪙"), ("trophy", "🏆"),
    ("duel", "⚔️"), ("unmute", "🔊"), ("pin", "📌"),
];

const ALIASES: [(&str, &str); 24] = [
    ("🔨", "🚫"), ("👢", "⛔"), ("🎰", "🎁"), ("⚔", "🔥"), ("💍", "❤"), ("💞", "❤"), ("💔", "❌"), ("😘", "❤"), ("🤗", "❤"),
    ("🥰", "❤"), ("💖", "❤"), ("👶", "👤"), ("🎲", "🎯"), ("🎭", "🎨"), ("🤝", "👥"), ("🔮", "✨"), ("🕰", "⏱"),
    ("🧹", "🗑"), ("📝", "✏"), ("🗳", "📥"), ("⚖", "🛡"), ("🔫", "⚡"), ("🥳", "🎁"), ("🎉", "🎁"),
];

/// Убирает VS16 (U+FE0F).
pub fn norm(e: &str) -> String {
    e.replace('\u{fe0f}', "")
}

pub static MAP: LazyLock<HashMap<String, String>> = LazyLock::new(|| {
    let mut base: HashMap<String, String> = serde_json::from_str(include_str!("emoji_map.json")).unwrap_or_default();
    for (c, like) in ALIASES {
        if !base.contains_key(c) {
            if let Some(v) = base.get(like).cloned() {
                base.insert(c.to_string(), v);
            }
        }
    }
    base
});

pub fn emoji(name: &str) -> String {
    let Some((_, base)) = FALLBACK.iter().find(|(n, _)| *n == name) else { return String::new() };
    match MAP.get(&norm(base)) {
        Some(id) => format!("<tg-emoji emoji-id=\"{id}\">{base}</tg-emoji>"),
        None => base.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn named() {
        assert_eq!(emoji("nope"), "");
        assert!(emoji("ok").contains('✅'));
        assert!(!MAP.is_empty());
    }
}
