//! Обработчики. Порядок регистрации — как в `bot/main.py`; первый вернувший `true` забирает обновление.
use crate::app::{Ctx, HandlerResult};
use teloxide::types::{CallbackQuery, Message};

pub mod stats;

pub async fn route_message(ctx: &Ctx, m: &Message) -> HandlerResult {
    // TODO(порт): owner, captcha, webpanel, giveaway, suggest, premium, images, download, menu, settings, setup, ranks,
    // cleanup, chatinfo, scan, social, moderation, history, logchat, reports, chatfilters, aimod — затем stats …
    if stats::on_message(ctx, m).await? {
        return Ok(true);
    }
    Ok(false)
}

pub async fn route_callback(ctx: &Ctx, q: &CallbackQuery) -> HandlerResult {
    if stats::on_callback(ctx, q).await? {
        return Ok(true);
    }
    Ok(false)
}
