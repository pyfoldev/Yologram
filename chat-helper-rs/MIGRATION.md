# Порт Chat Helper на Rust

Инструмент: [`rust-anything`](https://github.com/erkamyaman/rust-anything) (запись поведения Python-оригинала → проверка Rust по записи).
Оригинал: Python/aiogram, ~24 500 строк (в репозиторий не входит). Сводка `rust-anything analyze --whole-app`: 41 юнит, 24 «ready», 17 «caution», 0 «blocked».

## Принятые решения (ADR, кратко)
1. **Рантайм**: tokio. **Telegram**: teloxide (ядро), **БД**: rusqlite/sqlx (схема SQLite бота сохраняется), **HTTP**: axum (капча, панель, вебхук) + reqwest.
2. **Чистая логика** живёт в `src/*.rs` без Telegram/БД и сверяется с оригиналом: либо через `rust-anything record/diff` (`verify/<unit>-rs`), либо корпусом, записанным Python-скриптом (`tools/gen_*_fixture.py` → `tests/fixtures/*.json`).
3. **Совместимость**: тексты ответов, команды без слэша, форматы настроек и БД — как в оригинале.
4. **Известные осознанные отклонения**: целые — `i64` (в Python bigint: расхождение только при ставке ≈ 2⁶³).
5. `yt-dlp`, Pillow-карточки, nudenet — вызывать как внешние процессы/крейты на этапе «интеграции», не переписывать.

## Состояние
Сделано ✅ (всё собирается, `cargo test` зелёный, 40+ тестов):

| Модуль Python | Rust | Проверка |
|---|---|---|
| `bot/games/*` | `src/games.rs` | rust-anything: 1604/1606 записанных случаев (2 — bigint-ставки) |
| `bot/parsing.py` | `src/parsing.rs` | 1055 фраз из Python-корпуса, 0 расхождений |
| `bot/filters.py`, `bot/links.py` | `src/filters.rs`, `src/links.rs` | корпус `tests/fixtures/text.json` (нормализация, валидация regex, домены, белый список), 0 расхождений |
| `bot/db.py` (SQLite, ~180 методов) | `src/db.rs`, `src/schema.sql` | Rust читает БД, созданную Python (`tests/fixtures/py.db`), + сценарии экономики/варнов/промокодов |
| `spam`, `fmt`, `ttl`, `ranks`, `raid`, `plans`, `cleanup`, `targets`, `emoji`, `config`, `runtime` | одноимённые `src/*.rs` | юнит-тесты по логике Python-тестов |
| каркас: middleware (блок-лист, учёт, счётчик), роутинг, `Ctx` | `src/app.rs`, `src/handlers/mod.rs`, `src/bin/chat-helper.rs` | компилируется; polling запускается |
| `handlers/stats.py` + `keyboards` | `src/handlers/stats.rs`, `src/keyboards.rs` | юнит-тест форматирования |

Не перенесено ⏳ (порядок — как `include_router` в `bot/main.py`):
`mod_service`, `handlers/{owner, captcha, webpanel, giveaway, suggest, premium, images, download, menu, settings, setup, ranks, cleanup, chatinfo, scan, social, moderation, history, logchat, reports, chatfilters, aimod, quotes, mog, games, duels, economy, votes, rp, family, garden, entertainment, fun, ai}`,
`middlewares/{guard, memory, prefix}`, `captcha_server`, `panel_server`, `giveaway_server`, `webhook` + `updatequeue`, `ai`, `chatmind`, `aimod`, `imagegen`, `downloader` (yt-dlp), `cards` (PIL → `image`/`resvg`), `nsfw`, `audit`, `alerts`, `backup`, `members`, `metrics`, `diagnostics`, `emoji_inject`, `family`, `mog`, `rp_data`, `weather`, `media`.

Как добавлять обработчик: файл `src/handlers/<имя>.rs` с `on_message(ctx, m) -> HandlerResult` / `on_callback`, подключить в `handlers::route_*` в порядке оригинала; чистую логику — в отдельный модуль с корпус-тестом против Python (`tools/gen_*_fixture.py`).

## Как проверять
```bash
cargo test                                   # юнит-тесты + parity с Python-корпусом
# повтор записи/сверки через rust-anything (нужны Python-бот и его venv):
cd verify/games-rs && rust-anything record . && rust-anything build . && rust-anything diff .
```
