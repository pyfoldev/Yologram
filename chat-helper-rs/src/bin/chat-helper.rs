use chat_helper::{app, config::load_config, db::Database, runtime::Runtime};
use std::sync::Arc;
use teloxide::{dptree, prelude::*};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().with_max_level(tracing::Level::INFO).init();
    let cfg = load_config().map_err(|e| anyhow::anyhow!(e))?;
    let db = Arc::new(Database::connect(&cfg.db_path)?);
    let rt = Arc::new(Runtime::default());
    rt.load(&db)?;
    if cfg.owner_ids.is_empty() {
        tracing::warn!("OWNER_ID не задан: панель владельца недоступна");
    }
    let bot = Bot::new(&cfg.bot_token);
    let ctx = Arc::new(app::Ctx { bot: bot.clone(), db: db.clone(), cfg: cfg.clone(), rt: rt.clone(), http: reqwest::Client::new() });

    // фоновые задачи: сброс счётчиков сообщений раз в 5 с
    let d = db.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(5)).await;
            if let Err(e) = d.flush_messages() {
                tracing::error!("flush failed: {e}");
            }
        }
    });

    if cfg.mode == "webhook" {
        tracing::warn!("режим webhook ещё не перенесён — работаю в polling");
    }
    bot.delete_webhook().await.ok();
    let handler = dptree::entry()
        .branch(Update::filter_message().endpoint(|ctx: Arc<app::Ctx>, m: Message| async move {
            if let Err(e) = app::on_message(ctx, m).await {
                tracing::error!("handler failed: {e:#}");
            }
            Ok::<(), anyhow::Error>(())
        }))
        .branch(Update::filter_callback_query().endpoint(|ctx: Arc<app::Ctx>, q: CallbackQuery| async move {
            if let Err(e) = app::on_callback(ctx, q).await {
                tracing::error!("callback failed: {e:#}");
            }
            Ok::<(), anyhow::Error>(())
        }));
    let mut dp = Dispatcher::builder(bot, handler).dependencies(dptree::deps![ctx]).build();
    tracing::info!("polling mode");
    tokio::select! {
        _ = dp.dispatch() => {}
        _ = rt.wait_stop() => {}
        _ = tokio::signal::ctrl_c() => {}
    }
    db.close()?;
    Ok(())
}
