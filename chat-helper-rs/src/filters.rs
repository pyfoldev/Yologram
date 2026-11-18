//! Стоп-слова и фильтры по регулярным выражениям. Порт `bot/filters.py`.
//!
//! Крейт `regex` работает за линейное время и не поддерживает просмотры/обратные ссылки, так что «катастрофический откат»
//! невозможен — таймаут оригинала не нужен (ADR-7). Проверка `validate_regex` сохранена как есть.
use crate::db::Row;
use regex::{Regex, RegexBuilder};
use std::sync::LazyLock;
use unicode_general_category::{get_general_category, GeneralCategory as G};
use unicode_normalization::UnicodeNormalization;

pub const MAX_REGEX_LEN: usize = 200;
pub const MAX_WORD_LEN: usize = 64;
pub const MAX_FILTERS: usize = 50;
pub const MAX_SCAN: usize = 4000;

static CLEAN: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[\W_]+").unwrap());
static WS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());
static BAD_FEATURES: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\(\?(?:=|!|<=|<!|\(|P=|>)|\\[1-9]").unwrap());

fn lookalike(c: char) -> char {
    const FROM: &str = "aekmhopctxyb";
    const TO: &str = "аекмнорстхув";
    FROM.chars().position(|f| f == c).map_or(c, |i| TO.chars().nth(i).unwrap())
}

#[derive(Debug, PartialEq, Eq)]
pub struct FilterError(pub String);

pub fn normalize(text: &str) -> String {
    let s: String = text
        .nfkd()
        .filter(|c| !matches!(get_general_category(*c), G::NonspacingMark | G::Format))
        .collect::<String>()
        .to_lowercase()
        .chars()
        .map(lookalike)
        .collect();
    let s = CLEAN.replace_all(&s, " ");
    // `(.)\1+` -> `\1`
    let mut out = String::with_capacity(s.len());
    let mut prev: Option<char> = None;
    for c in s.chars() {
        if Some(c) != prev {
            out.push(c);
        }
        prev = Some(c);
    }
    WS.replace_all(&out, " ").trim().to_string()
}

fn strip_classes_and_escapes(p: &str) -> String {
    let chars: Vec<char> = p.chars().collect();
    let (mut out, mut i, mut in_class) = (String::new(), 0, false);
    while i < chars.len() {
        let c = chars[i];
        if c == '\\' {
            i += 2;
            out.push('E');
            continue;
        }
        if in_class {
            if c == ']' {
                in_class = false;
            }
            i += 1;
            continue;
        }
        if c == '[' {
            in_class = true;
            out.push('C');
        } else {
            out.push(c);
        }
        i += 1;
    }
    out
}

pub fn validate_regex(pattern: &str) -> Result<(), FilterError> {
    let n = pattern.chars().count();
    if n == 0 || n > MAX_REGEX_LEN {
        return Err(FilterError(format!("Выражение должно быть от 1 до {MAX_REGEX_LEN} символов.")));
    }
    if BAD_FEATURES.is_match(pattern) {
        return Err(FilterError("Просмотры вперёд/назад, условия и обратные ссылки не поддерживаются.".into()));
    }
    if let Err(e) = RegexBuilder::new(pattern).case_insensitive(true).build() {
        return Err(FilterError(format!("Некорректное выражение: {e}")));
    }
    let flat: Vec<char> = strip_classes_and_escapes(pattern).chars().collect();
    let mut stack = vec![];
    for (i, c) in flat.iter().enumerate() {
        if *c == '(' {
            stack.push(i);
        } else if *c == ')' {
            if let Some(start) = stack.pop() {
                let nxt = flat.get(i + 1).copied().unwrap_or(' ');
                if matches!(nxt, '*' | '+' | '{') && flat[start + 1..i].iter().any(|c| matches!(c, '*' | '+' | '{' | '|')) {
                    return Err(FilterError("Вложенные повторы и повтор группы с «|» запрещены (риск зависания).".into()));
                }
            }
        }
    }
    Ok(())
}

pub struct FilterSet {
    words: Vec<(i64, String)>,
    regexes: Vec<(i64, Regex)>,
}

impl FilterSet {
    pub fn new(rows: &[Row]) -> Self {
        let (mut words, mut regexes) = (vec![], vec![]);
        for r in rows {
            let id = r["id"].as_i64().unwrap_or(0);
            let pat = r["pattern"].as_str().unwrap_or("");
            if r["kind"] == "word" {
                let w = normalize(pat);
                if !w.is_empty() {
                    words.push((id, format!(" {w} ")));
                }
            } else {
                match RegexBuilder::new(pat).case_insensitive(true).build() {
                    Ok(rx) => regexes.push((id, rx)),
                    Err(_) => tracing::warn!("filter {id}: сохранённое выражение не компилируется, пропущено"),
                }
            }
        }
        FilterSet { words, regexes }
    }
    pub fn is_empty(&self) -> bool {
        self.words.is_empty() && self.regexes.is_empty()
    }
    /// id сработавшего фильтра (наименьший) или None.
    pub fn first_match(&self, text: &str) -> Option<i64> {
        let text: String = text.chars().take(MAX_SCAN).collect();
        if text.is_empty() {
            return None;
        }
        let padded = format!(" {} ", normalize(&text));
        let mut hits: Vec<i64> = self.words.iter().filter(|(_, w)| padded.contains(w.as_str())).map(|(id, _)| *id).collect();
        hits.extend(self.regexes.iter().filter(|(_, rx)| rx.is_match(&text)).map(|(id, _)| *id));
        hits.into_iter().min()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn rows(v: &[(i64, &str, &str)]) -> Vec<Row> {
        v.iter().map(|(i, k, p)| json!({"id": i, "kind": k, "pattern": p}).as_object().unwrap().clone()).collect()
    }
    #[test]
    fn normalizes() {
        assert_eq!(normalize("ПрИвЕт,,,   Мир!!!"), "привет мир");
        assert_eq!(normalize("xy"), "ху"); // латиница -> кириллица
        assert_eq!(normalize("ёёё"), "е"); // ё -> е (NFKD), повторы схлопнуты
    }
    #[test]
    fn word_and_regex() {
        let fs = FilterSet::new(&rows(&[(3, "word", "Спам"), (5, "regex", r"купи\s+\d+")]));
        assert_eq!(fs.first_match("это СПААААМ!"), Some(3));
        assert_eq!(fs.first_match("Купи   123"), Some(5));
        assert_eq!(fs.first_match("спамить"), None);
        assert!(FilterSet::new(&[]).is_empty());
    }
    #[test]
    fn validation() {
        assert!(validate_regex(r"abc\d+").is_ok());
        assert!(validate_regex("(a+)+").is_err());
        assert!(validate_regex("(?=a)").is_err());
        assert!(validate_regex(r"(a)\1").is_err());
        assert!(validate_regex("(").is_err());
        assert!(validate_regex(&"a".repeat(201)).is_err());
        assert!(validate_regex(r"(\+)+").is_ok()); // экранированный + не считается
    }
}
