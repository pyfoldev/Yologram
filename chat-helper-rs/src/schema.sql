CREATE TABLE IF NOT EXISTS msg_hourly(
    chat_id INTEGER, user_id INTEGER, hour_ts INTEGER, n INTEGER,
    PRIMARY KEY(chat_id, user_id, hour_ts));
CREATE TABLE IF NOT EXISTS msg_daily(
    chat_id INTEGER, user_id INTEGER, day_ts INTEGER, n INTEGER,
    PRIMARY KEY(chat_id, user_id, day_ts));
CREATE TABLE IF NOT EXISTS warns(
    chat_id INTEGER, user_id INTEGER, n INTEGER,
    PRIMARY KEY(chat_id, user_id));
CREATE TABLE IF NOT EXISTS mod_log(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, actor_id INTEGER,
    target_id INTEGER, action TEXT, reason TEXT, ts INTEGER, duration INTEGER);
CREATE TABLE IF NOT EXISTS warn_events(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, user_id INTEGER, ts INTEGER,
    expires_at INTEGER, reason TEXT, actor INTEGER);
CREATE INDEX IF NOT EXISTS warn_events_user ON warn_events(chat_id, user_id, expires_at);
CREATE TABLE IF NOT EXISTS reports(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, reporter INTEGER, target INTEGER, message_id INTEGER,
    text TEXT, reason TEXT, status TEXT DEFAULT 'open', ts INTEGER, handled_by INTEGER);
CREATE INDEX IF NOT EXISTS reports_msg ON reports(chat_id, message_id);
CREATE TABLE IF NOT EXISTS filters(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, kind TEXT, pattern TEXT, action TEXT,
    created_by INTEGER, ts INTEGER);
CREATE INDEX IF NOT EXISTS filters_chat ON filters(chat_id);
CREATE TABLE IF NOT EXISTS link_allow(
    chat_id INTEGER, domain TEXT, created_by INTEGER, PRIMARY KEY(chat_id, domain));
CREATE TABLE IF NOT EXISTS aimod_events(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, user_id INTEGER, message_id INTEGER, category TEXT,
    confidence INTEGER, action TEXT, text TEXT, status TEXT DEFAULT 'open', ts INTEGER, handled_by INTEGER);
CREATE TABLE IF NOT EXISTS giveaways(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, creator INTEGER, step TEXT, text TEXT, photo TEXT,
    winners INTEGER DEFAULT 1, publish_at INTEGER, ends_at INTEGER, status TEXT DEFAULT 'draft', message_id INTEGER,
    created_at INTEGER, result TEXT, shown_count INTEGER DEFAULT 0);
CREATE INDEX IF NOT EXISTS giveaways_status ON giveaways(status);
CREATE TABLE IF NOT EXISTS giveaway_entries(
    gid INTEGER, user_id INTEGER, ts INTEGER, PRIMARY KEY(gid, user_id));
CREATE TABLE IF NOT EXISTS giveaway_checks(
    gid INTEGER, user_id INTEGER, answer TEXT, attempts INTEGER DEFAULT 0, expires_at INTEGER, PRIMARY KEY(gid, user_id));
CREATE TABLE IF NOT EXISTS quotes(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, author_id INTEGER, author_name TEXT, text TEXT, ts INTEGER,
    added_by INTEGER, added_at INTEGER, message_id INTEGER, card TEXT);
CREATE INDEX IF NOT EXISTS quotes_chat ON quotes(chat_id, author_id);
CREATE TABLE IF NOT EXISTS mog_ratings(
    fp TEXT PRIMARY KEY, avatar INTEGER, name INTEGER, meaning INTEGER, ts INTEGER);
CREATE TABLE IF NOT EXISTS mog_results(
    chat_id INTEGER, winner INTEGER, loser INTEGER, ts INTEGER);
CREATE INDEX IF NOT EXISTS mog_results_chat ON mog_results(chat_id, winner);
CREATE TABLE IF NOT EXISTS suggestions(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, user_id INTEGER, user_name TEXT, text TEXT, ts INTEGER,
    status TEXT DEFAULT 'new', handled_by INTEGER, handled_at INTEGER);
CREATE INDEX IF NOT EXISTS suggestions_chat ON suggestions(chat_id, status);
CREATE TABLE IF NOT EXISTS premium(
    user_id INTEGER PRIMARY KEY, until INTEGER, since INTEGER, payments INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS payments(
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, charge_id TEXT, amount INTEGER, ts INTEGER,
    recurring INTEGER DEFAULT 0, refunded INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS user_prefs(
    user_id INTEGER PRIMARY KEY, ai_model TEXT);
CREATE TABLE IF NOT EXISTS promo_codes(
    code TEXT PRIMARY KEY, days INTEGER, uses_left INTEGER, created_by INTEGER, created_at INTEGER);
CREATE TABLE IF NOT EXISTS promo_uses(
    code TEXT, user_id INTEGER, ts INTEGER, PRIMARY KEY(code, user_id));
CREATE TABLE IF NOT EXISTS settings(
    chat_id INTEGER, key TEXT, value TEXT, PRIMARY KEY(chat_id, key));
CREATE TABLE IF NOT EXISTS wallets(
    chat_id INTEGER, user_id INTEGER, balance INTEGER, last_bonus_day INTEGER DEFAULT -1,
    wins INTEGER DEFAULT 0, PRIMARY KEY(chat_id, user_id));
CREATE TABLE IF NOT EXISTS captchas(
    token TEXT PRIMARY KEY, chat_id INTEGER, user_id INTEGER, answer TEXT,
    expires_at INTEGER, message_id INTEGER);
CREATE TABLE IF NOT EXISTS users(
    user_id INTEGER PRIMARY KEY, username TEXT, name TEXT);
CREATE INDEX IF NOT EXISTS users_username ON users(username);
CREATE TABLE IF NOT EXISTS chat_members(
    chat_id INTEGER, user_id INTEGER, first_seen INTEGER, last_seen INTEGER,
    joined_at INTEGER, active INTEGER DEFAULT 1, PRIMARY KEY(chat_id, user_id));
CREATE TABLE IF NOT EXISTS chats(
    chat_id INTEGER PRIMARY KEY, title TEXT, type TEXT, username TEXT,
    added_at INTEGER, last_seen INTEGER, active INTEGER DEFAULT 1);
CREATE TABLE IF NOT EXISTS bot_bans(
    kind TEXT, id INTEGER, reason TEXT, ts INTEGER, PRIMARY KEY(kind, id));
CREATE TABLE IF NOT EXISTS owner_log(
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, owner_id INTEGER, action TEXT, detail TEXT);
CREATE TABLE IF NOT EXISTS marriages(
    chat_id INTEGER, u1 INTEGER, u2 INTEGER, since INTEGER, b1 INTEGER DEFAULT -1, b2 INTEGER DEFAULT -1,
    PRIMARY KEY(chat_id, u1, u2));
CREATE TABLE IF NOT EXISTS relations(
    chat_id INTEGER, u1 INTEGER, u2 INTEGER, since INTEGER, PRIMARY KEY(chat_id, u1, u2));
CREATE TABLE IF NOT EXISTS parents(
    chat_id INTEGER, child INTEGER, parent INTEGER, since INTEGER, PRIMARY KEY(chat_id, child, parent));
CREATE TABLE IF NOT EXISTS family_requests(
    id TEXT PRIMARY KEY, chat_id INTEGER, kind TEXT, from_user INTEGER, to_user INTEGER,
    message_id INTEGER, expires_at INTEGER);
CREATE TABLE IF NOT EXISTS ranks(
    chat_id INTEGER, user_id INTEGER, rank INTEGER, by_user INTEGER, ts INTEGER, PRIMARY KEY(chat_id, user_id));
CREATE TABLE IF NOT EXISTS cmd_access(
    chat_id INTEGER, command TEXT, min_rank INTEGER, PRIMARY KEY(chat_id, command));
CREATE TABLE IF NOT EXISTS mod_state(
    chat_id INTEGER, user_id INTEGER, kind TEXT, until INTEGER, reason TEXT, actor INTEGER, ts INTEGER,
    PRIMARY KEY(chat_id, user_id, kind));
CREATE TABLE IF NOT EXISTS chat_texts(
    chat_id INTEGER, key TEXT, text TEXT, PRIMARY KEY(chat_id, key));
CREATE TABLE IF NOT EXISTS profiles(
    chat_id INTEGER, user_id INTEGER, nick TEXT, title TEXT, motto TEXT, about TEXT, PRIMARY KEY(chat_id, user_id));
CREATE TABLE IF NOT EXISTS reputation(
    chat_id INTEGER, user_id INTEGER, score INTEGER DEFAULT 0, PRIMARY KEY(chat_id, user_id));
CREATE TABLE IF NOT EXISTS rep_log(
    chat_id INTEGER, from_user INTEGER, to_user INTEGER, ts INTEGER);
CREATE TABLE IF NOT EXISTS awards(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, user_id INTEGER, degree INTEGER, by_user INTEGER, ts INTEGER);
CREATE TABLE IF NOT EXISTS notes(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, user_id INTEGER, kind TEXT, text TEXT, ts INTEGER);
CREATE TABLE IF NOT EXISTS timers(
    id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, user_id INTEGER, due INTEGER, text TEXT);
CREATE TABLE IF NOT EXISTS family_bonus(
    chat_id INTEGER, user_id INTEGER, day INTEGER, PRIMARY KEY(chat_id, user_id));
CREATE INDEX IF NOT EXISTS mod_log_target ON mod_log(chat_id, target_id, ts);
CREATE INDEX IF NOT EXISTS chat_members_active ON chat_members(chat_id, active, last_seen);
CREATE INDEX IF NOT EXISTS mod_state_until ON mod_state(until);
DROP INDEX IF EXISTS msg_hourly_hour;
CREATE INDEX IF NOT EXISTS msg_hourly_cover ON msg_hourly(chat_id, hour_ts, user_id, n);
CREATE INDEX IF NOT EXISTS rep_log_idx ON rep_log(chat_id, from_user, to_user, ts);
CREATE TABLE IF NOT EXISTS farm(
    chat_id INTEGER, user_id INTEGER, last_ts INTEGER, PRIMARY KEY(chat_id, user_id));
CREATE TABLE IF NOT EXISTS usage(
    chat_id INTEGER, kind TEXT, day INTEGER, n INTEGER, PRIMARY KEY(chat_id, kind, day));
CREATE TABLE IF NOT EXISTS garden(
    chat_id INTEGER, user_id INTEGER, data TEXT, PRIMARY KEY(chat_id, user_id));
CREATE TABLE IF NOT EXISTS update_queue(
    update_id INTEGER PRIMARY KEY, ts INTEGER, attempts INTEGER DEFAULT 0, payload TEXT);
CREATE TABLE IF NOT EXISTS duels(
    id TEXT PRIMARY KEY, chat_id INTEGER, data TEXT, expires_at INTEGER);
