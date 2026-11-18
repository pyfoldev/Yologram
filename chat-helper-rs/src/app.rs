//! Каркас приложения: общий контекст, middleware и маршрутизация обновлений Telegram.
//!
//! Устройство повторяет `bot/main.py`: обновление проходит «внешние» middleware (блок-лист → учёт пользователей →
//! счётчик сообщений), затем по очереди роутеры-обработчики `handlers::*` в том же порядке, что и в оригинале;
//! первый обработчик, вернувший `Ok(true)`, «забирает» обновление.
use crate::{config::Config, db::{now_ts, Database}, runtime::Runtime};
use std::collections::HashSet;
use std::sync::Arc;
use teloxide::{
    prelude::*,
    types::{CallbackQuery, InlineKeyboardMarkup, LinkPreviewOptions, Message, ParseMode, ReplyParameters},
};

pub struct Ctx {
    pub bot: Bot,
    pub db: Arc<Database>,
    pub cfg: Config,
    pub rt: Arc<Runtime>,
    pub http: reqwest::Client,
}

pub type HandlerResult = anyhow::Result<bool>;

impl Ctx {
    pub fn owner_ids(&self) -> &HashSet<i64> {
        &self.cfg.owner_ids
    }

    /// Отправить HTML-сообщение в чат (превью ссылок выключены, как `link_preview_is_disabled` в оригинале).
    pub async fn say(&self, chat_id: ChatId, text: &str, kb: Option<InlineKeyboardMarkup>) -> anyhow::Result<Message> {
        let mut req = self.bot.send_message(chat_id, text).parse_mode(ParseMode::Html).link_preview_options(LinkPreviewOptions {
            is_disabled: true,
            url: None,
            prefer_small_media: false,
            prefer_large_media: false,
            show_above_text: false,
        });
        if let Some(kb) = kb {
            req = req.reply_markup(kb);
        }
        Ok(req.await?)
    }

    /// Ответить на сообщение (`message.answer` в aiogram не цитирует; `reply_to` — цитирует).
    pub async fn reply_to(&self, m: &Message, text: &str, kb: Option<InlineKeyboardMarkup>) -> anyhow::Result<Message> {
        let mut req = self.bot.send_message(m.chat.id, text).parse_mode(ParseMode::Html).reply_parameters(ReplyParameters::new(m.id));
        if let Some(kb) = kb {
            req = req.reply_markup(kb);
        }
        Ok(req.await?)
    }
}

pub fn is_group(m: &Message) -> bool {
    m.chat.is_group() || m.chat.is_supergroup()
}

/// Сообщение, на которое ответили (сервисное сообщение темы форума — не ответ). Порт `mod_service.real_reply`.
pub fn real_reply(m: &Message) -> Option<&Message> {
    m.reply_to_message().filter(|r| r.forum_topic_created().is_none())
}

pub fn full_name(u: &teloxide::types::User) -> String {
    u.full_name()
}

// ---------- middleware ----------

/// Блок-лист и режим обслуживания (`bot/middlewares/blocklist.py` — упрощённо, пока без выхода из заблокированных чатов).
pub fn blocked(ctx: &Ctx, user: Option<i64>, chat: Option<i64>) -> bool {
    let owners = ctx.owner_ids();
    if user.is_some_and(|u| owners.contains(&u)) {
        return false;
    }
    if ctx.rt.maintenance.load(std::sync::atomic::Ordering::SeqCst) {
        return true;
    }
    user.is_some_and(|u| ctx.rt.banned_users.read().unwrap().contains(&u)) || chat.is_some_and(|c| ctx.rt.banned_chats.read().unwrap().contains(&c))
}

const SERVICE_IDS: [i64; 3] = [777000, 136817688, 1087968824];

/// Учёт пользователей и чатов, счётчик сообщений (`tracker.py` + `counter.py`).
pub fn track_message(ctx: &Ctx, m: &Message) {
    let now = now_ts();
    if let Some(u) = &m.from {
        if !u.is_bot && !SERVICE_IDS.contains(&(u.id.0 as i64)) {
            let _ = ctx.db.remember_user(u.id.0 as i64, u.username.as_deref(), &u.full_name());
            if is_group(m) {
                let _ = ctx.db.touch_member(m.chat.id.0, u.id.0 as i64, now, false);
            }
        }
    }
    if is_group(m) {
        let _ = ctx.db.upsert_chat(m.chat.id.0, m.chat.title(), if m.chat.is_supergroup() { "supergroup" } else { "group" }, m.chat.username(), now);
        if let Some(u) = m.from.as_ref().filter(|u| !u.is_bot) {
            ctx.db.record_message(m.chat.id.0, u.id.0 as i64, now);
        }
    }
}

pub async fn on_message(ctx: Arc<Ctx>, m: Message) -> anyhow::Result<()> {
    if blocked(&ctx, m.from.as_ref().map(|u| u.id.0 as i64), Some(m.chat.id.0)) {
        return Ok(());
    }
    track_message(&ctx, &m);
    crate::handlers::route_message(&ctx, &m).await?;
    Ok(())
}

pub async fn on_callback(ctx: Arc<Ctx>, q: CallbackQuery) -> anyhow::Result<()> {
    if blocked(&ctx, Some(q.from.id.0 as i64), q.message.as_ref().map(|m| m.chat().id.0)) {
        return Ok(());
    }
    crate::handlers::route_callback(&ctx, &q).await?;
    Ok(())
}
