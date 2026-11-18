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
| Модуль Python | Rust | Проверка | Статус |
|---|---|---|---|
| `bot/games/*` (rps, slots, tictactoe, garden) | `src/games.rs` | rust-anything: 1604/1606 записанных случаев (2 — bigint-ставки) | ✅ |
| `bot/parsing.py` | `src/parsing.rs` | 1055 фраз из Python-корпуса, 0 расхождений | ✅ |
| `bot/spam.py` | `src/spam.rs` | юнит-тесты по `tests/test_spam_raid.py` | ✅ |
| `bot/fmt.py` | `src/fmt.rs` | юнит-тесты по `tests/test_fmt.py` | ✅ |
| `bot/ttl.py` | `src/ttl.rs` | юнит-тест | ✅ |
| `bot/config.py`, `db.py`, `filters.py`, `ranks.py`, `targets.py`, `raid.py`, … | — | — | ⏳ |
| `bot/middlewares/*`, `bot/handlers/*` (37 файлов) | — | — | ⏳ (после БД + teloxide-каркаса) |
| `captcha_server`, `panel_server`, `webhook`, `giveaway_server` | — | — | ⏳ (axum) |
| `ai.py`, `imagegen.py`, `downloader.py` (yt-dlp), `cards.py` (PIL), `nsfw*` | — | — | ⏳ |

## Как проверять
```bash
cargo test                                   # юнит-тесты + parity с Python-корпусом
# повтор записи/сверки через rust-anything (нужны Python-бот и его venv):
cd verify/games-rs && rust-anything record . && rust-anything build . && rust-anything diff .
```
