//! Ссылки в сообщениях: домены и белый список. Порт `bot/links.py`.
use regex::Regex;
use std::collections::HashSet;
use std::sync::LazyLock;

static RE: LazyLock<Regex> = LazyLock::new(|| {
    // lookbehind `(?<![@\w.\-])` проверяется вручную
    Regex::new(r"(?i)https?://(?P<h1>[^\s/?#]+)|(?P<h2>www\.[^\s/?#]+)|(?P<h3>(?:[\w\-]+\.)+(?:com|ru|org|net|io|me|su|xyz|top|site))\b").unwrap()
});

const TRIM: &[char] = &['.', ',', ';', ':', '!', '?', ')', '(', '"', '\'', '«', '»'];

/// «https://www.Example.com/a» → «example.com»; «*.x.org» сохраняется; мусор → None.
pub fn normalize_domain(raw: &str) -> Option<String> {
    let mut s = raw.trim().to_lowercase();
    if s.is_empty() || s.contains(' ') {
        return None;
    }
    let wildcard = s.starts_with("*.");
    if wildcard {
        s = s[2..].to_string();
    }
    if let Some(p) = s.find("://") {
        let rest = &s[p + 3..];
        s = rest.split(['/', '?', '#']).next().unwrap_or("").to_string(); // netloc
    }
    let s0 = s.split('/').next().unwrap().split('?').next().unwrap().split('#').next().unwrap().to_string();
    let s1 = s0.rsplit('@').next().unwrap().split(':').next().unwrap().trim_matches(TRIM).to_string();
    let mut s = s1;
    if let Some(r) = s.strip_prefix("www.") {
        s = r.to_string();
    }
    if !s.contains('.') || s.starts_with('.') || s.contains("..") {
        return None;
    }
    let ascii = idna::domain_to_ascii(&s).ok()?;
    Some(format!("{}{}", if wildcard { "*." } else { "" }, ascii))
}

/// Сущности Telegram: (тип, url для text_link, подстрока для url).
pub struct Ent<'a> {
    pub kind: &'a str,
    pub url: Option<&'a str>,
    pub text: Option<&'a str>,
}

pub fn extract_hosts(text: &str, entities: &[Ent]) -> HashSet<String> {
    let mut hosts = HashSet::new();
    for e in entities {
        let d = match e.kind {
            "text_link" if e.url.is_some_and(|u| !u.is_empty()) => normalize_domain(e.url.unwrap()),
            "url" => e.text.and_then(normalize_domain),
            _ => continue,
        };
        if let Some(d) = d {
            hosts.insert(d);
        }
    }
    for c in RE.captures_iter(text) {
        let m = c.get(0).unwrap();
        if c.name("h3").is_some() {
            // `(?<![@\w.\-])`
            if let Some(prev) = text[..m.start()].chars().next_back() {
                if prev == '@' || prev == '.' || prev == '-' || prev.is_alphanumeric() || prev == '_' {
                    continue;
                }
            }
        }
        let raw = c.name("h1").or(c.name("h2")).or(c.name("h3")).unwrap().as_str();
        if let Some(d) = normalize_domain(raw) {
            hosts.insert(d);
        }
    }
    hosts
}

/// Точное совпадение; поддомены — только если в списке явно «*.домен».
pub fn host_allowed(host: &str, allow: &HashSet<String>) -> bool {
    if allow.contains(host) {
        return true;
    }
    let parts: Vec<&str> = host.split('.').collect();
    (0..parts.len().saturating_sub(1)).any(|i| allow.contains(&format!("*.{}", parts[i..].join("."))))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn domains() {
        assert_eq!(normalize_domain("https://www.Example.com/a").as_deref(), Some("example.com"));
        assert_eq!(normalize_domain("*.x.org").as_deref(), Some("*.x.org"));
        assert_eq!(normalize_domain("мусор"), None);
        assert_eq!(normalize_domain("пример.рф").as_deref(), Some("xn--e1afmkfd.xn--p1ai"));
        let h = extract_hosts("смотри t.me/x и https://a.example.com/p, mail@evil.com", &[]);
        assert!(h.contains("a.example.com") && !h.contains("evil.com"));
        let allow: HashSet<String> = ["*.example.com".to_string()].into();
        assert!(host_allowed("a.b.example.com", &allow) && host_allowed("example.com", &allow) && !host_allowed("badexample.com", &allow));
    }
}
