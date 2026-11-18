//! Разбор команд модерации, сроков и статистики. Порт `bot/parsing.py`.
//!
//! Срок — `chrono`-free: микросекунды (`Span`), как внутреннее представление `timedelta`.
use regex::Regex;
use std::sync::LazyLock;

pub const MAX_DAYS: f64 = 366.0;
const DAY_US: f64 = 86_400_000_000.0;

/// Длительность в микросекундах.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Span(pub i64);

impl Span {
    pub fn from_days(days: f64) -> Span {
        Span((days * DAY_US).round() as i64)
    }
    pub fn seconds(self) -> i64 {
        self.0 / 1_000_000
    }
    pub fn minutes(m: i64) -> Span {
        Span(m * 60_000_000)
    }
    pub fn hours(h: i64) -> Span {
        Span(h * 3_600_000_000)
    }
    pub fn days(d: i64) -> Span {
        Span(d * 86_400_000_000)
    }
}

fn cap(days: f64) -> Span {
    Span::from_days(days.min(MAX_DAYS))
}

fn unit_days(c: &str) -> Option<f64> {
    Some(match c {
        "м" => 1.0 / 1440.0,
        "ч" => 1.0 / 24.0,
        "д" => 1.0,
        "н" => 7.0,
        _ => return None,
    })
}

static DUR_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^(\d+)\s*([мчдн])$").unwrap());

/// ASCII-число; слишком длинное насыщается (в Python — bigint, потом `min(n, 10**7)`).
fn parse_uint(s: &str) -> Option<i64> {
    if s.is_empty() || !s.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    Some(s.parse::<i64>().unwrap_or(i64::MAX))
}

/// Как `int()` в Python: принимает любые десятичные цифры Unicode (`\p{Nd}`), насыщается при переполнении.
fn parse_py_int(s: &str) -> Option<i64> {
    let mut n: i64 = 0;
    for c in s.chars() {
        let d = nd_value(c)? as i64;
        n = n.saturating_mul(10).saturating_add(d);
    }
    (!s.is_empty()).then_some(n)
}

/// Значение цифры Nd: блоки по 10 подряд идущих кодпоинтов; «математические» цифры (1D7CE..1D7FF) — 5 блоков подряд.
fn nd_value(c: char) -> Option<u32> {
    if let Some(d) = c.to_digit(10) {
        return Some(d);
    }
    if !c.is_numeric() {
        return None;
    }
    let u = c as u32;
    if (0x1D7CE..=0x1D7FF).contains(&u) {
        return Some((u - 0x1D7CE) % 10);
    }
    let mut zero = u;
    while u - zero < 9 && char::from_u32(zero - 1).is_some_and(|p| p.is_numeric()) {
        zero -= 1;
    }
    Some(u - zero)
}

pub fn parse_duration(text: &str) -> Option<Span> {
    let t = text.trim();
    let m = DUR_RE.captures(t)?;
    let n = parse_py_int(&m[1])?;
    if n <= 0 {
        return None;
    }
    let u = unit_days(&m[2].to_lowercase())?;
    Some(cap(n.min(10_000_000) as f64 * u))
}

const SLASH: [(&str, &str); 12] = [
    ("ban", "бан"), ("unban", "разбан"), ("mute", "мут"), ("unmute", "размут"),
    ("kick", "кик"), ("warn", "варн"), ("unwarn", "снять варн"), ("warns", "варны"),
    ("del", "удалить"), ("pin", "закреп"), ("unpin", "открепить"), ("purge", "чистка"),
];
const NAMES: [&str; 15] = [
    "снять варн", "разбан", "размут", "открепить", "удалить", "закреп", "чистка", "варны", "варн", "бан",
    "мут", "кик", "-смс", "!пург", "пург",
];

static NAME_RE: LazyLock<Regex> = LazyLock::new(|| {
    let alts: Vec<String> = NAMES.iter().map(|n| regex::escape(n).replace(' ', r"\s+")).collect();
    // lookahead `(?=\s|$)` заменён проверкой после матча
    Regex::new(&format!(r"(?i)^({})", alts.join("|"))).unwrap()
});
static TARGET_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^(@\w+|\d{5,})$").unwrap());
static REASON_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?is)(?:^|\s)причина(?::|\s)\s*(.+)$").unwrap());
static SLASH_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?s)^/(\w+)(?:@\w+)?(?:\s+(.*))?$").unwrap());
static WS_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());

fn num_word(w: &str) -> Option<i64> {
    Some(match w {
        "один" | "одну" | "одна" => 1,
        "два" | "две" => 2,
        "три" => 3,
        "четыре" => 4,
        "пять" => 5,
        "шесть" => 6,
        "семь" => 7,
        "восемь" => 8,
        "девять" => 9,
        "десять" => 10,
        "пятнадцать" => 15,
        "двадцать" => 20,
        "тридцать" => 30,
        _ => return None,
    })
}

fn unit_word(w: &str) -> Option<f64> {
    Some(match w {
        "минута" | "минуту" | "минуты" | "минут" | "мин" => 1.0 / 1440.0,
        "час" | "часа" | "часов" => 1.0 / 24.0,
        "день" | "дня" | "дней" | "сутки" | "суток" => 1.0,
        "неделя" | "неделю" | "недели" | "недель" => 7.0,
        _ => return None,
    })
}

fn is_forever(w: &str) -> bool {
    matches!(w, "навсегда" | "перманентно")
}
fn timed(n: &str) -> bool {
    matches!(n, "бан" | "мут" | "варн")
}
fn allow_target(n: &str) -> bool {
    matches!(n, "бан" | "разбан" | "мут" | "размут" | "кик" | "варн" | "снять варн" | "варны")
}
fn allow_reason(n: &str) -> bool {
    matches!(n, "бан" | "мут" | "кик" | "варн")
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModCommand {
    pub name: String,
    pub target: Option<String>,
    pub duration: Option<Span>,
    pub reason: String,
}

fn is_int(t: &str) -> bool {
    !t.is_empty() && t.chars().all(|c| c.is_ascii_digit()) // «²» и юникодные цифры — не числа
}

#[derive(Debug, PartialEq)]
enum Kind {
    None,
    Forever,
    Span,
}

fn parse_span(mut tokens: &[String]) -> Option<(Kind, Option<Span>)> {
    if tokens.first().map(String::as_str) == Some("на") {
        tokens = &tokens[1..];
        if tokens.is_empty() {
            return None;
        }
    }
    if tokens.is_empty() {
        return Some((Kind::None, None));
    }
    if tokens.len() == 1 {
        let t = tokens[0].as_str();
        if is_forever(t) {
            return Some((Kind::Forever, None));
        }
        if let Some(d) = parse_duration(t) {
            return Some((Kind::Span, Some(d)));
        }
        if let Some(u) = unit_word(t) {
            return Some((Kind::Span, Some(cap(u))));
        }
        return None;
    }
    if tokens.len() == 2 {
        if let Some(u) = unit_word(&tokens[1]) {
            let n = if is_int(&tokens[0]) { Some(parse_uint(&tokens[0]).unwrap_or(i64::MAX)) } else { num_word(&tokens[0]) };
            if let Some(n) = n.filter(|n| *n > 0) {
                return Some((Kind::Span, Some(cap(n as f64 * u))));
            }
        }
    }
    None
}

/// «7д», «2 недели», «на месяц» → срок; иначе None. Для команд массовой чистки.
pub fn parse_period(text: &str) -> Option<Span> {
    let tokens: Vec<String> = text.to_lowercase().split_whitespace().map(String::from).collect();
    match parse_span(&tokens) {
        Some((Kind::Span, d)) => d,
        _ => None,
    }
}

pub fn parse_mod_command(text: &str) -> Option<ModCommand> {
    let text = text.trim();
    if text.is_empty() {
        return None;
    }
    if text.starts_with('/') {
        return parse_slash(text);
    }
    let m = NAME_RE.find(text)?;
    // эквивалент `(?=\s|$)`
    if text[m.end()..].chars().next().is_some_and(|c| !c.is_whitespace()) {
        return None;
    }
    let name = WS_RE.replace_all(&m.as_str().to_lowercase(), " ").to_string();
    let name = match name.as_str() {
        "-смс" | "пург" | "!пург" => "чистка".to_string(),
        _ => name,
    };
    let mut rest = text[m.end()..].trim().to_string();
    let mut reason = String::new();
    if let Some(rm) = REASON_RE.captures(&rest) {
        if !allow_reason(&name) {
            return None;
        }
        reason = rm[1].trim().to_string();
        let start = rm.get(0).unwrap().start();
        rest = rest[..start].trim().to_string();
    }
    let orig: Vec<String> = rest.split_whitespace().map(String::from).collect();
    let mut tokens: Vec<String> = rest.to_lowercase().split_whitespace().map(String::from).collect();

    if name == "чистка" {
        if tokens.is_empty() {
            return Some(ModCommand { name, target: None, duration: None, reason: String::new() });
        }
        if tokens.len() == 1 && is_int(&tokens[0]) && parse_uint(&tokens[0]).is_some_and(|n| n >= 1) {
            let r = tokens[0].clone();
            return Some(ModCommand { name, target: None, duration: None, reason: r });
        }
        return None;
    }

    let mut target = None;
    for i in 0..tokens.len() {
        let t = &tokens[i];
        let nxt = tokens.get(i + 1).map(String::as_str).unwrap_or("");
        if is_int(t) && unit_word(nxt).is_some() {
            continue; // «мут 10000 минут» — это срок, а не id
        }
        if TARGET_RE.is_match(t) {
            if !allow_target(&name) {
                return None;
            }
            // исходный регистр ника; индекс в orig совпадает, пока lower() не меняет число слов
            target = Some(orig.get(i).cloned().unwrap_or_else(|| t.clone()));
            tokens.remove(i);
            break;
        }
    }
    let (kind, mut delta) = parse_span(&tokens)?;
    if kind != Kind::None && !timed(&name) {
        return None;
    }
    if kind == Kind::Forever {
        if name == "варн" {
            return None;
        }
        delta = if name == "мут" { Some(cap(MAX_DAYS)) } else { None };
    }
    Some(ModCommand { name, target, duration: delta, reason })
}

fn parse_slash(text: &str) -> Option<ModCommand> {
    let m = SLASH_RE.captures(text)?;
    let cmd = m[1].to_lowercase();
    let name = SLASH.iter().find(|(k, _)| *k == cmd)?.1.to_string();
    let mut parts: Vec<&str> = m.get(2).map_or("", |g| g.as_str()).trim().split_whitespace().collect();
    let (mut target, mut duration) = (None, None);
    if parts.first().is_some_and(|p| TARGET_RE.is_match(p)) {
        target = Some(parts.remove(0).to_string());
    }
    if !parts.is_empty() && timed(&name) {
        if let Some(d) = parse_duration(parts[0]) {
            duration = Some(d);
            parts.remove(0);
        }
    }
    Some(ModCommand { name, target, duration, reason: parts.join(" ") })
}

// ---------- статистика ----------
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StatsPeriod {
    Hours(i64),
    All,
}

static STATS_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?is)^(?:стата|статистика)(?:\s+(.+))?$").unwrap());
static HOURS_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^(\d+)\s*(?:час|часа|часов|ч)$").unwrap());

pub fn parse_stats_command(text: &str) -> Option<StatsPeriod> {
    let m = STATS_RE.captures(text.trim())?;
    let arg = m.get(1).map_or("", |g| g.as_str()).trim().to_lowercase();
    match arg.as_str() {
        "" | "день" | "сутки" => return Some(StatsPeriod::Hours(24)),
        "неделя" | "неделю" => return Some(StatsPeriod::Hours(168)),
        "месяц" => return Some(StatsPeriod::Hours(720)),
        "вся" | "всё" | "все" | "всего" => return Some(StatsPeriod::All),
        _ => {}
    }
    let h = HOURS_RE.captures(&arg)?;
    let n = parse_uint(&h[1])?;
    (1..=168).contains(&n).then_some(StatsPeriod::Hours(n))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cmd(t: &str) -> ModCommand {
        parse_mod_command(t).unwrap_or_else(|| panic!("не команда: {t}"))
    }

    #[test]
    fn duration() {
        assert_eq!(parse_duration("30м"), Some(Span::minutes(30)));
        assert_eq!(parse_duration("2ч"), Some(Span::hours(2)));
        assert_eq!(parse_duration("1н"), Some(Span::days(7)));
        assert_eq!(parse_duration("0м"), None);
        assert_eq!(parse_duration("абв"), None);
        assert_eq!(parse_duration("999999д"), Some(Span::days(366)));
        assert_eq!(parse_duration("99999999999999999999д"), Some(Span::days(366)));
    }

    #[test]
    fn plain_text_is_not_a_command() {
        for t in [
            "бан бы дать тебе", "бан это слишком жёстко", "банка пива", "бананы", "привет", "кик его надо бы",
            "мут бы ему", "удалить бы это", "варн спам", "чистка бы тут", "бан 2ч и всё", "мут абв", "мут 0м",
            "кик 2ч", "закреп это", "бан ему рано давать", "бан на", "мут на часок отдохнуть",
        ] {
            assert!(parse_mod_command(t).is_none(), "{t}");
        }
    }

    #[test]
    fn bare_and_two_word() {
        for n in ["бан", "разбан", "размут", "кик", "варн", "варны", "удалить", "закреп", "открепить"] {
            assert_eq!(cmd(n).name, n);
            assert_eq!(cmd(&(n.to_uppercase() + "  ")).name, n);
        }
        assert_eq!(cmd("снять варн").name, "снять варн");
        assert_eq!(cmd("Снять  варн @a").target.as_deref(), Some("@a"));
    }

    #[test]
    fn durations_in_commands() {
        assert_eq!(cmd("Мут 2ч").duration, Some(Span::hours(2)));
        assert_eq!(cmd("бан один день").duration, Some(Span::days(1)));
        assert_eq!(cmd("мут два часа").duration, Some(Span::hours(2)));
        assert_eq!(cmd("мут на час").duration, Some(Span::hours(1)));
        assert_eq!(cmd("мут навсегда").duration, Some(Span::days(366)));
        assert_eq!(cmd("бан навсегда").duration, None);
        assert!(parse_mod_command("варн навсегда").is_none());
    }

    #[test]
    fn target_reason_purge() {
        let c = cmd("бан @User 1д причина: флуд тут");
        assert_eq!((c.target.as_deref(), c.duration, c.reason.as_str()), (Some("@User"), Some(Span::days(1)), "флуд тут"));
        assert_eq!(cmd("мут 12345 минут").target, None);
        assert_eq!(cmd("кик 123456").target.as_deref(), Some("123456"));
        assert_eq!(cmd("чистка 50").reason, "50");
        assert_eq!(cmd("пург").name, "чистка");
        assert!(parse_mod_command("чистка 0").is_none());
        assert!(parse_mod_command("удалить @abc").is_none());
    }

    #[test]
    fn slash() {
        let c = cmd("/ban@mybot @u 2ч спамил много");
        assert_eq!((c.name.as_str(), c.target.as_deref(), c.duration, c.reason.as_str()), ("бан", Some("@u"), Some(Span::hours(2)), "спамил много"));
        assert!(parse_mod_command("/start").is_none());
    }

    #[test]
    fn stats() {
        assert_eq!(parse_stats_command("стата"), Some(StatsPeriod::Hours(24)));
        assert_eq!(parse_stats_command("Статистика неделю"), Some(StatsPeriod::Hours(168)));
        assert_eq!(parse_stats_command("стата вся"), Some(StatsPeriod::All));
        assert_eq!(parse_stats_command("стата 12 часов"), Some(StatsPeriod::Hours(12)));
        assert_eq!(parse_stats_command("стата 500ч"), None);
        assert_eq!(parse_period("2 недели"), Some(Span::days(14)));
    }
}
