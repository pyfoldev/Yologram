//! «стата» и топы. Порт `bot/handlers/stats.py`.
use crate::app::{full_name, is_group, real_reply, Ctx, HandlerResult};
use crate::db::now_ts;
use crate::emoji::emoji;
use crate::fmt::{pre_table, Cell};
use crate::keyboards::stats_keyboard;
use crate::parsing::{parse_stats_command, StatsPeriod};
use crate::targets::{listed, mention};
use teloxide::{prelude::*, types::{CallbackQuery, Message, ParseMode}};

pub fn period_title(key: &str) -> String {
    match key {
        "all" => "за всё время".into(),
        "24" => "за 24 часа".into(),
        "168" => "за неделю".into(),
        "720" => "за месяц".into(),
        k => format!("за {k} ч."),
    }
}

pub fn format_stats(total: i64, top: &[(String, i64)], title: &str) -> String {
    let head = format!("{} <b>Статистика {title}</b>", emoji("stats"));
    if total == 0 {
        return head + "\nПока нет сообщений.";
    }
    let rows: Vec<Vec<Cell>> = top.iter().enumerate().map(|(i, (name, n))| vec![Cell::from(i + 1), Cell::Html(name.clone()), Cell::from(n)]).collect();
    let table = pre_table(&rows, Some(&["#", "Участник", "Сообщ."]), &[3, 20, 7], false);
    format!("{head} · {total}\n{table}")
}

async fn name_of(ctx: &Ctx, chat_id: i64, user_id: i64) -> String {
    if let Ok(Some(n)) = ctx.db.user_name(user_id) {
        return n;
    }
    match ctx.bot.get_chat_member(ChatId(chat_id), UserId(user_id as u64)).await {
        Ok(m) => m.user.full_name(),
        Err(_) => format!("id{user_id}"),
    }
}

pub async fn build_stats(ctx: &Ctx, chat_id: i64, key: &str) -> anyhow::Result<String> {
    let since = if key == "all" { None } else { Some(now_ts() - key.parse::<i64>().unwrap_or(24) * 3600) };
    let (total, top) = ctx.db.count_by_user(chat_id, since, 10)?;
    let mut named = vec![];
    for (uid, n) in top {
        let name = name_of(ctx, chat_id, uid).await;
        named.push((listed(&ctx.db, uid, &name, Some(28)), n));
    }
    Ok(format_stats(total, &named, &period_title(key)))
}

fn enabled(ctx: &Ctx, m: &Message) -> bool {
    !is_group(m) || ctx.db.get_settings(m.chat.id.0).map(|s| s["stats_on"].int() != 0).unwrap_or(true)
}

pub async fn on_message(ctx: &Ctx, m: &Message) -> HandlerResult {
    let Some(text) = m.text() else { return Ok(false) };
    let Some(p) = parse_stats_command(text) else { return Ok(false) };
    if !enabled(ctx, m) {
        return Ok(false);
    }
    let key = match p {
        StatsPeriod::All => "all".to_string(),
        StatsPeriod::Hours(h) => h.to_string(),
    };
    if let Some(u) = real_reply(m).and_then(|r| r.from.as_ref()) {
        let since = if key == "all" { None } else { Some(now_ts() - key.parse::<i64>().unwrap_or(24) * 3600) };
        let n = ctx.db.user_total(m.chat.id.0, u.id.0 as i64, since)?;
        let text = format!("{} {} {}: <b>{n}</b> сообщ.", emoji("stats"), mention(&ctx.db, u.id.0 as i64, &full_name(u), None), period_title(&key));
        ctx.say(m.chat.id, &text, None).await?;
        return Ok(true);
    }
    let text = build_stats(ctx, m.chat.id.0, &key).await?;
    ctx.say(m.chat.id, &text, Some(stats_keyboard(&key))).await?;
    Ok(true)
}

pub async fn on_callback(ctx: &Ctx, q: &CallbackQuery) -> HandlerResult {
    let Some(key) = q.data.as_deref().and_then(|d| d.strip_prefix("stats:")) else { return Ok(false) };
    if key != "all" && !key.chars().all(|c| c.is_ascii_digit()) || key.is_empty() {
        ctx.bot.answer_callback_query(q.id.clone()).await?;
        return Ok(true);
    }
    if let Some(msg) = q.regular_message() {
        let text = build_stats(ctx, msg.chat.id.0, key).await?;
        ctx.bot.edit_message_text(msg.chat.id, msg.id, text).parse_mode(ParseMode::Html).reply_markup(stats_keyboard(key)).await.ok();
    }
    ctx.bot.answer_callback_query(q.id.clone()).await?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn format() {
        assert!(format_stats(0, &[], "за 24 часа").ends_with("Пока нет сообщений."));
        let out = format_stats(7, &[("Вася".into(), 5), ("Петя".into(), 2)], "за неделю");
        assert!(out.contains("· 7\n") && out.contains("1. Вася — 5") && out.contains("2. Петя — 2"), "{out}");
        assert_eq!(period_title("36"), "за 36 ч.");
    }
}
