//! Оформление сообщений: цитаты и «таблицы». Порт `bot/fmt.py`.

const ELLIPSIS: &str = "…";
pub const MAX_PRE: usize = 3500;

/// Ячейка: обычный текст (экранируется и обрезается) или готовый HTML (как есть).
#[derive(Debug, Clone)]
pub enum Cell {
    Text(String),
    Html(String),
}

impl<T: ToString> From<T> for Cell {
    fn from(v: T) -> Self {
        Cell::Text(v.to_string())
    }
}

/// `html.escape(s, quote=True)` из Python.
pub fn escape(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => o.push_str("&amp;"),
            '<' => o.push_str("&lt;"),
            '>' => o.push_str("&gt;"),
            '"' => o.push_str("&quot;"),
            '\'' => o.push_str("&#x27;"),
            _ => o.push(c),
        }
    }
    o
}

fn esc(c: &Cell) -> String {
    match c {
        Cell::Html(h) => h.clone(),
        Cell::Text(t) => escape(t),
    }
}

/// Цитата вокруг УЖЕ экранированного HTML. `expandable = None` — решает длина.
pub fn quote(body_html: &str, expandable: Option<bool>) -> String {
    let expandable = expandable.unwrap_or_else(|| body_html.chars().count() > 300 || body_html.matches('\n').count() >= 4);
    let open = if expandable { "<blockquote expandable>" } else { "<blockquote>" };
    format!("{open}{body_html}</blockquote>")
}

fn cut(c: &Cell, width: usize) -> Cell {
    match c {
        Cell::Html(_) => c.clone(),
        Cell::Text(t) => {
            let t = t.split_whitespace().collect::<Vec<_>>().join(" ");
            if t.chars().count() <= width {
                Cell::Text(t)
            } else {
                let keep = width.saturating_sub(1).max(1);
                Cell::Text(t.chars().take(keep).collect::<String>() + ELLIPSIS)
            }
        }
    }
}

fn lines_block(mut lines: Vec<String>, quoted: bool) -> String {
    let mut body = lines.join("\n");
    while body.chars().count() > MAX_PRE && lines.len() > 1 {
        lines.pop();
        body = lines.join("\n");
    }
    if quoted {
        let exp = lines.len() > 14 || body.chars().count() > 1200;
        quote(&body, Some(exp))
    } else {
        body
    }
}

/// Строки-списком в цитате: «1. Имя — значение · значение».
pub fn pre_table(rows: &[Vec<Cell>], header: Option<&[&str]>, maxw: &[usize], quoted: bool) -> String {
    if rows.is_empty() {
        return String::new();
    }
    let ncols = rows[0].len();
    let caps: Vec<usize> = (0..ncols).map(|i| 2 * maxw.get(i).or(maxw.last()).copied().unwrap_or(22)).collect();
    let numbered = header.is_some_and(|h| h.first().is_some_and(|f| *f == "#" || *f == "№"));
    let mut lines = vec![];
    if let Some(h) = header {
        let names: Vec<&&str> = h.iter().filter(|x| **x != "#" && **x != "№").collect();
        if names.len() >= 4 {
            let tail: Vec<String> = names[1..].iter().map(|n| n.to_lowercase()).collect();
            lines.push(format!("<i>{} — {}</i>", escape(names[0]), escape(&tail.join(" · "))));
        }
    }
    for r in rows {
        let cells: Vec<String> = r.iter().enumerate().map(|(i, c)| esc(&cut(c, caps.get(i).copied().unwrap_or(44)))).collect();
        let (head, rest) = if numbered && cells.len() >= 2 {
            (format!("{}. {}", cells[0], cells[1]), &cells[2..])
        } else {
            (cells[0].clone(), &cells[1..])
        };
        let joined: Vec<&str> = rest.iter().filter(|c| !c.is_empty()).map(String::as_str).collect();
        lines.push(if rest.iter().any(|c| !c.is_empty()) { format!("{head} — {}", joined.join(" · ")) } else { head });
    }
    lines_block(lines, quoted)
}

/// Пары «параметр: значение» цитатой.
pub fn kv_table(pairs: &[(&str, Cell)]) -> String {
    let lines = pairs
        .iter()
        .map(|(k, v)| format!("<b>{}:</b> {}", escape(&cut(&Cell::Text(k.to_string()), 28).text()), esc(&cut(v, 48))))
        .collect();
    lines_block(lines, true)
}

impl Cell {
    fn text(&self) -> String {
        match self {
            Cell::Text(t) | Cell::Html(t) => t.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn row(v: &[&str]) -> Vec<Cell> {
        v.iter().map(|s| Cell::from(*s)).collect()
    }

    #[test]
    fn quote_collapse() {
        assert_eq!(quote("коротко", None), "<blockquote>коротко</blockquote>");
        assert!(quote(&"x".repeat(400), None).starts_with("<blockquote expandable>"));
        assert!(quote("a\nb\nc\nd\ne", None).starts_with("<blockquote expandable>"));
        assert!(quote(&"длинно".repeat(80), Some(false)).starts_with("<blockquote>"));
    }

    #[test]
    fn table() {
        let out = pre_table(&[row(&["1", "Вася <b>", "5"]), row(&["2", "Петя", "120"])], Some(&["#", "Участник", "Сообщ."]), &[22], true);
        assert!(out.contains("1. Вася &lt;b&gt; — 5") && out.contains("2. Петя — 120"));
        let wide = pre_table(&[row(&["🥕 морковь", "20", "2 ч", "45"])], Some(&["Культура", "Семена", "Растёт", "Урожай"]), &[22], true);
        assert!(wide.contains("<i>Культура — семена · растёт · урожай</i>") && wide.contains("морковь — 20 · 2 ч · 45"));
        assert!(pre_table(&[row(&[&"а".repeat(200), "1"])], None, &[10, 3], true).contains('…'));
        let rows: Vec<_> = (0..2000).map(|i| row(&[&i.to_string(), &format!("имя {i}"), "ru"])).collect();
        assert!(pre_table(&rows, Some(&["#", "a", "b"]), &[22], true).chars().count() < 4000);
    }

    #[test]
    fn kv() {
        let out = kv_table(&[("ID", Cell::from(123)), ("Статус", Cell::from("Участник"))]);
        assert!(out.contains("<b>ID:</b> 123") && out.contains("<b>Статус:</b> Участник") && out.starts_with("<blockquote>"));
    }
}
