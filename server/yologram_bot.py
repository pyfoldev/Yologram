#!/usr/bin/env python3
"""Yologram admin bot: user statistics, service-message broadcast and server tools, all with inline buttons.

Stdlib only. Reads the Yologram sync server's database (db.sqlite) and writes its `notices` table, which the apps
poll to show a message in their "Yologram" chat.

Config (environment, see yologram-bot.env.example):
  BOT_TOKEN    token from @BotFather (required)
  ADMIN_IDS    Telegram user ids allowed to use the bot, space/comma separated. Empty: the bot only tells you your id.
  EMOJI_PACK   short name of a custom-emoji pack (t.me/addemoji/<name>). Needs a bot owner with Telegram Premium for
               buttons, or a bot that bought extra usernames on Fragment for text. Everything falls back to plain emoji.
  TZ_OFFSET    hours from UTC for the daily digest (default 5)
"""
import csv, html, io, json, os, shutil, sqlite3, subprocess, time, traceback, urllib.error, urllib.request, uuid

TOKEN = os.environ.get("BOT_TOKEN", "")
ADMINS = {int(x) for x in os.environ.get("ADMIN_IDS", "").replace(",", " ").split() if x.lstrip("-").isdigit()}
DB = os.environ.get("NULLGRAM_DB", "/opt/nullgram/data/db.sqlite")
UPDATES = os.environ.get("NULLGRAM_UPDATES", "/opt/nullgram/updates")
EMOJI_PACK = os.environ.get("EMOJI_PACK", "").strip()
TZ_OFFSET = int(os.environ.get("TZ_OFFSET", "5"))
DIGEST_HOUR = int(os.environ.get("DIGEST_HOUR", "9"))
API = f"https://api.telegram.org/bot{TOKEN}/"

DAY = 86400
CUSTOM = {}      # plain emoji (without variation selector) -> custom_emoji_id
STATE = {}       # admin id -> pending input, e.g. {"await": "notice"}


# ---------------------------------------------------------------- database
def db():
    c = sqlite3.connect(DB, timeout=10)
    c.execute("pragma busy_timeout=10000")
    return c


def setup():
    c = db()
    c.execute("create table if not exists notices(id integer primary key autoincrement, ts integer, text text)")
    c.execute("create table if not exists bot_settings(k text primary key, v text)")
    c.execute("create table if not exists known_users(id integer primary key, first_seen integer)")
    c.execute("create table if not exists known_crashes(sig text primary key, ts integer)")
    c.commit()
    c.close()


def setting(k, default="0"):
    c = db()
    r = c.execute("select v from bot_settings where k=?", (k,)).fetchone()
    c.close()
    return r[0] if r else default


def set_setting(k, v):
    c = db()
    c.execute("insert into bot_settings(k,v) values(?,?) on conflict(k) do update set v=excluded.v", (k, str(v)))
    c.commit()
    c.close()


def q1(sql, *a):
    c = db()
    try:
        r = c.execute(sql, a).fetchone()
        return r[0] if r else 0
    finally:
        c.close()


def qa(sql, *a):
    c = db()
    try:
        return c.execute(sql, a).fetchall()
    finally:
        c.close()


def ex(sql, *a):
    c = db()
    try:
        c.execute(sql, a)
        c.commit()
    finally:
        c.close()


# ---------------------------------------------------------------- telegram api
def tg(method, _retry=True, **params):
    req = urllib.request.Request(API + method, json.dumps(params).encode(), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=70) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        try:
            body = json.load(e)
        except Exception:
            body = {"ok": False, "description": str(e)}
        if e.code == 429 and _retry:
            time.sleep(body.get("parameters", {}).get("retry_after", 2))
            return tg(method, False, **params)
        # custom emoji / button style not allowed for this bot: send the same message with plain emoji and buttons
        d = body.get("description", "").lower()
        if e.code == 400 and _retry and any(w in d for w in ("custom emoji", "icon_custom", "button_style", "style")):
            params = strip_fancy(params)
            return tg(method, False, **params)
        return body
    except Exception as e:
        return {"ok": False, "description": str(e)}


def strip_fancy(params):
    p = dict(params)
    if "text" in p and isinstance(p["text"], str):
        import re
        p["text"] = re.sub(r"<tg-emoji emoji-id=\"\d+\">(.*?)</tg-emoji>", r"\1", p["text"])
    km = p.get("reply_markup")
    if km and "inline_keyboard" in km:
        rows = []
        for row in km["inline_keyboard"]:
            nr = []
            for b in row:
                b = dict(b)
                icon = b.pop("icon_custom_emoji_id", None)
                b.pop("style", None)
                if icon:
                    plain = next((e for e, i in CUSTOM.items() if i == icon), "")
                    b["text"] = (plain + " " + b["text"]).strip()
                nr.append(b)
            rows.append(nr)
        p["reply_markup"] = {"inline_keyboard": rows}
    return p


def load_emoji_pack():
    if not EMOJI_PACK:
        return
    r = tg("getStickerSet", name=EMOJI_PACK)
    if not r.get("ok"):
        print("emoji pack not loaded:", r.get("description"))
        return
    for s in r["result"].get("stickers", []):
        if s.get("custom_emoji_id") and s.get("emoji"):
            CUSTOM.setdefault(s["emoji"].replace("️", ""), s["custom_emoji_id"])
    print("custom emoji loaded:", len(CUSTOM))


def fmt(text):
    """Wraps every plain emoji that the custom pack has into <tg-emoji>; text itself is trusted HTML."""
    if not CUSTOM:
        return text
    out, i = [], 0
    while i < len(text):
        c = text[i]
        key = c.replace("️", "")
        if key in CUSTOM and ord(c) > 0x2000:
            j = i + 1
            if j < len(text) and text[j] == "️":
                j += 1
            out.append(f'<tg-emoji emoji-id="{CUSTOM[key]}">{text[i:j]}</tg-emoji>')
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def btn(label, data, emoji="", style=None, url=None):
    b = {"text": label}
    if url:
        b["url"] = url
    else:
        b["callback_data"] = data
    if emoji:
        icon = CUSTOM.get(emoji.replace("️", ""))
        if icon:
            b["icon_custom_emoji_id"] = icon
        else:
            b["text"] = emoji + " " + label
    if style:
        b["style"] = style
    return b


def send_params(text, kb):
    p = {"text": fmt(text), "parse_mode": "HTML", "link_preview_options": {"is_disabled": True}}
    if kb is not None:
        p["reply_markup"] = {"inline_keyboard": kb}
    return p


def show(chat, msg_id, text, kb=None):
    p = send_params(text, kb)
    if msg_id:
        r = tg("editMessageText", chat_id=chat, message_id=msg_id, **p)
        if r.get("ok") or "not modified" in r.get("description", ""):
            return r
    return tg("sendMessage", chat_id=chat, **p)


def send_document(chat, name, data, caption=""):
    boundary = uuid.uuid4().hex
    body = b""
    for k, v in (("chat_id", str(chat)), ("caption", caption)):
        body += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    body += f'--{boundary}\r\nContent-Disposition: form-data; name="document"; filename="{name}"\r\nContent-Type: text/csv\r\n\r\n'.encode()
    body += data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(API + "sendDocument", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        urllib.request.urlopen(req, timeout=60).read()
    except Exception as e:
        print("sendDocument failed:", e)


# ---------------------------------------------------------------- helpers
def esc(s):
    return html.escape(str(s if s is not None else ""))


def ago(ts):
    if not ts:
        return "никогда"
    d = int(time.time()) - int(ts)
    if d < 90:
        return "только что"
    if d < 3600:
        return f"{d // 60} мин назад"
    if d < DAY:
        return f"{d // 3600} ч назад"
    return f"{d // DAY} дн назад"


def local_time(ts):
    return time.strftime("%d.%m %H:%M", time.gmtime(int(ts) + TZ_OFFSET * 3600))


def bar(n, total, width=10):
    if not total:
        return "░" * width
    k = max(0, min(width, round(width * n / total)))
    return "█" * k + "░" * (width - k)


def human(n):
    for u in ("Б", "КБ", "МБ", "ГБ"):
        if n < 1024 or u == "ГБ":
            return f"{n:.0f} {u}" if u == "Б" else f"{n:.1f} {u}"
        n /= 1024


def stats_counts():
    now = int(time.time())
    total = q1("select count(*) from users where seen>0")
    return {
        "total": total,
        "d1": q1("select count(*) from users where seen>?", now - DAY),
        "d7": q1("select count(*) from users where seen>?", now - 7 * DAY),
        "d30": q1("select count(*) from users where seen>?", now - 30 * DAY),
        "badge_on": q1("select count(*) from users where seen>0 and badge=1"),
        "badge_off": q1("select count(*) from users where seen>0 and badge=0"),
        "banners": q1("select count(*) from users where banner_ts>0"),
        "thoughts": q1("select count(*) from users where thought is not null and thought_ts>?", now - DAY),
        "new_today": q1("select count(*) from known_users where first_seen>?", now - DAY),
        "new_week": q1("select count(*) from known_users where first_seen>?", now - 7 * DAY),
        "installs": q1("select count(distinct install) from diag where ts>?", now - 7 * DAY),
    }


# ---------------------------------------------------------------- screens
def main_menu():
    return [
        [btn("Статистика", "stats", "📊"), btn("Активность", "active", "👥")],
        [btn("Отправить сообщение", "send", "📣", "primary")],
        [btn("История", "hist", "🗂"), btn("Версии", "vers", "📈")],
        [btn("Устройства", "dev", "📱"), btn("Краши", "crash", "🐞")],
        [btn("Функции", "feat", "⚙️"), btn("Пользователь", "user", "🔎")],
        [btn("Обновление", "upd", "🚀"), btn("Сервер", "srv", "🩺")],
        [btn("Оповещения", "notif", "🔔"), btn("Экспорт", "export", "📤")],
    ]


def back(extra=None):
    row = [btn("В меню", "menu", "🏠")]
    return [(extra or []), row] if extra else [row]


def screen_menu():
    s = stats_counts()
    return (f"✨ <b>Yologram · панель управления</b>\n\n"
            f"👥 Пользователей в клиенте: <b>{s['total']}</b>\n"
            f"🟢 Активны за сутки: <b>{s['d1']}</b>\n"
            f"🆕 Новых за сутки: <b>{s['new_today']}</b>\n\n"
            f"Выберите раздел ниже:"), main_menu()


def screen_stats():
    s = stats_counts()
    t = max(s["total"], 1)
    text = (f"📊 <b>Статистика</b>\n\n"
            f"👥 Всего в клиенте: <b>{s['total']}</b>\n"
            f"🟢 За 24 часа: <b>{s['d1']}</b>  {bar(s['d1'], t)}\n"
            f"🔵 За 7 дней: <b>{s['d7']}</b>  {bar(s['d7'], t)}\n"
            f"🟣 За 30 дней: <b>{s['d30']}</b>  {bar(s['d30'], t)}\n\n"
            f"🆕 Новых за сутки: <b>{s['new_today']}</b>, за неделю: <b>{s['new_week']}</b>\n"
            f"🏷 Значок виден у <b>{s['badge_on']}</b>, скрыт у <b>{s['badge_off']}</b>\n"
            f"🖼 Баннеров: <b>{s['banners']}</b> · 💭 Мыслей сейчас: <b>{s['thoughts']}</b>\n"
            f"📲 Установок с диагностикой (7 дн): <b>{s['installs']}</b>")
    return text, [[btn("Обновить", "stats", "🔄")], *back()]


def screen_active():
    now = int(time.time())
    rows = []
    for i in range(7):
        a, b = now - (i + 1) * DAY, now - i * DAY
        n = q1("select count(*) from users where seen>? and seen<=?", a, b)
        rows.append((time.strftime("%d.%m", time.gmtime(b + TZ_OFFSET * 3600)), n))
    top = max([n for _, n in rows] + [1])
    lines = "\n".join(f"<code>{d}</code> {bar(n, top, 12)} <b>{n}</b>" for d, n in rows)
    return f"👥 <b>Активность по дням</b>\nПосещения по последнему «привет» клиента.\n\n{lines}", back()


def screen_hist():
    rows = qa("select id, ts, text from notices order by id desc limit 8")
    if not rows:
        return "🗂 <b>История рассылок</b>\n\nПока ничего не отправлялось.", back([btn("Отправить", "send", "📣")])
    lines = "\n\n".join(f"<b>#{i}</b> · {local_time(ts)}\n{esc(t[:140])}{'…' if len(t) > 140 else ''}" for i, ts, t in rows)
    kb = [[btn(f"Удалить #{i}", f"del:{i}", "🗑") for i, _, _ in rows[:4]],
          [btn(f"Удалить #{i}", f"del:{i}", "🗑") for i, _, _ in rows[4:8]]]
    kb = [r for r in kb if r]
    return f"🗂 <b>История рассылок</b>\n\n{lines}\n\n<i>Удаление убирает сообщение у тех, кто его ещё не получил.</i>", kb + back()


def screen_vers():
    rows = qa("select version, count(distinct install) from diag where ts>? group by version order by 2 desc limit 10", int(time.time()) - 7 * DAY)
    if not rows:
        return "📈 <b>Версии</b>\n\nДанных диагностики пока нет.", back()
    total = sum(n for _, n in rows)
    lines = "\n".join(f"<code>{esc(v):<10}</code> {bar(n, total)} <b>{n}</b>" for v, n in rows)
    return f"📈 <b>Версии клиента</b> (7 дней)\n\n{lines}", back()


def screen_dev():
    rows = qa("select device, count(distinct install) from diag where ts>? group by device order by 2 desc limit 10", int(time.time()) - 7 * DAY)
    if not rows:
        return "📱 <b>Устройства</b>\n\nДанных пока нет.", back()
    total = sum(n for _, n in rows)
    lines = "\n".join(f"{bar(n, total, 8)} <b>{n}</b> · {esc(d)}" for d, n in rows)
    langs = qa("select lang, count(distinct install) from diag where ts>? group by lang order by 2 desc limit 5", int(time.time()) - 7 * DAY)
    ls = " · ".join(f"{esc(l or '—')}: <b>{n}</b>" for l, n in langs)
    return f"📱 <b>Устройства</b> (7 дней)\n\n{lines}\n\n🌐 Языки: {ls}", back()


def crash_rows():
    return qa("select kind, sig, count(*), count(distinct install), max(ts) from diag where kind in ('crash','error') and ts>? "
              "group by kind, sig order by (kind='crash') desc, 3 desc limit 6", int(time.time()) - 7 * DAY)


def screen_crash():
    rows = crash_rows()
    if not rows:
        return "🐞 <b>Краши</b>\n\nЗа неделю ошибок нет. 🎉", back()
    lines, kb = [], []
    for n, (kind, sig, cnt, users, last) in enumerate(rows):
        icon = "💥" if kind == "crash" else "⚠️"
        lines.append(f"{icon} <b>{cnt}×</b> ({users} польз.) · {ago(last)}\n<code>{esc(sig[:90])}</code>")
        kb.append([btn(f"Трасса {n + 1}", f"tr:{n}", "🔍")])
    return "🐞 <b>Краши и ошибки</b> (7 дней)\n\n" + "\n\n".join(lines), kb + back()


def screen_trace(n):
    rows = crash_rows()
    if n >= len(rows):
        return screen_crash()
    sig = rows[n][1]
    r = qa("select trace, version, device from diag where sig=? order by ts desc limit 1", sig)
    trace, ver, dev = r[0] if r else ("", "", "")
    return f"🔍 <b>Трасса {n + 1}</b>\n{esc(ver)} · {esc(dev)}\n\n<pre>{esc(trace[:3300])}</pre>", [[btn("К крашам", "crash", "🐞")], *back()]


def screen_feat():
    now = int(time.time())
    feats, installs = {}, 0
    for (f,) in qa("select features from diag where kind='ping' and ts>? group by install", now - 7 * DAY):
        installs += 1
        try:
            for k, v in json.loads(f).items():
                if v is True:
                    feats[k] = feats.get(k, 0) + 1
        except Exception:
            pass
    if not feats:
        return "⚙️ <b>Функции</b>\n\nДанных пока нет.", back()
    lines = "\n".join(f"<code>{esc(k):<18}</code> {bar(v, installs, 8)} <b>{v}</b>" for k, v in sorted(feats.items(), key=lambda x: -x[1]))
    return f"⚙️ <b>Какие функции включены</b>\nПо {installs} установкам за 7 дней.\n\n{lines}", back()


def screen_user(uid=None):
    if not uid:
        STATE_PROMPT = "🔎 <b>Поиск пользователя</b>\n\nПришлите Telegram ID пользователя числом."
        return STATE_PROMPT, [[btn("Отмена", "menu", "✖️")]]
    r = qa("select seen, badge, banner_ts, thought, thought_ts from users where id=?", uid)
    if not r:
        return f"🔎 Пользователь <code>{uid}</code> не найден в клиенте.", [[btn("Искать ещё", "user", "🔎")], *back()]
    seen, badge, bts, thought, tts = r[0]
    th = esc(thought) if thought and tts and tts > time.time() - DAY else "—"
    text = (f"🔎 <b>Пользователь</b> <code>{uid}</code>\n\n"
            f"🕒 Последний визит: <b>{ago(seen)}</b>\n"
            f"🏷 Значок: <b>{'показывается' if badge else 'скрыт'}</b>\n"
            f"🖼 Баннер: <b>{'есть' if bts else 'нет'}</b>\n"
            f"💭 Мысль: {th}")
    kb = [[btn("Скрыть значок" if badge else "Показать значок", f"badge:{uid}:{0 if badge else 1}", "🏷")],
          [btn("Убрать мысль", f"clr:t:{uid}", "💭"), btn("Убрать баннер", f"clr:b:{uid}", "🖼")],
          [btn("Искать ещё", "user", "🔎")], *back()]
    return text, kb


def latest_json():
    try:
        with open(os.path.join(UPDATES, "latest.json"), encoding="utf8") as f:
            return json.load(f)
    except Exception:
        return None


def screen_upd():
    j = latest_json()
    if not j:
        return "🚀 <b>Обновление</b>\n\nНа сервере нет опубликованной сборки.", back()
    p = os.path.join(UPDATES, "latest.json")
    text = (f"🚀 <b>Опубликованное обновление</b>\n\n"
            f"Версия: <b>{esc(j.get('versionName'))}</b> (<code>{j.get('versionCode')}</code>)\n"
            f"Размер: <b>{human(j.get('size', 0))}</b>\n"
            f"Опубликовано: <b>{ago(os.path.getmtime(p))}</b>\n"
            f"SHA-256: <code>{esc(str(j.get('sha256', ''))[:16])}…</code>\n\n"
            f"📝 Описание:\n{esc(j.get('notes') or '—')}")
    return text, [[btn("Изменить описание", "notes", "✏️")], *back()]


def run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=8).stdout.strip()
    except Exception:
        return ""


def screen_srv():
    up = run(["uptime", "-p"]) or "—"
    load = os.getloadavg()[0] if hasattr(os, "getloadavg") else 0
    du = shutil.disk_usage("/")
    mem = ""
    try:
        m = {l.split(":")[0]: int(l.split()[1]) for l in open("/proc/meminfo")}
        mem = f"{human((m['MemTotal'] - m['MemAvailable']) * 1024)} из {human(m['MemTotal'] * 1024)}"
    except Exception:
        mem = "—"
    svcs = []
    for s in ("nullgram", "yologram-web", "yologram-bot"):
        st = run(["systemctl", "is-active", s]) or "?"
        svcs.append(f"{'🟢' if st == 'active' else '🔴'} {s}: {st}")
    dbs = human(os.path.getsize(DB)) if os.path.exists(DB) else "—"
    last = q1("select max(ts) from diag")
    return (f"🩺 <b>Сервер</b>\n\n⏱ {esc(up)}\n🧠 Нагрузка: <b>{load:.2f}</b>\n💾 Память: <b>{mem}</b>\n"
            f"📀 Диск: <b>{human(du.free)}</b> свободно из {human(du.total)}\n🗄 База: <b>{dbs}</b>\n"
            f"📡 Последний пинг клиента: <b>{ago(last)}</b>\n\n" + "\n".join(svcs)), [[btn("Обновить", "srv", "🔄")], *back()]


def toggle_btn(key, label, emoji):
    on = setting(key, "0") == "1"
    return btn(f"{label}: {'вкл' if on else 'выкл'}", f"tg:{key}", emoji, "success" if on else None)


def screen_notif():
    return ("🔔 <b>Оповещения</b>\n\nБот сам напишет вам, когда это случится.",
            [[toggle_btn("alert_new", "Новые пользователи", "🆕")],
             [toggle_btn("alert_crash", "Новые краши", "💥")],
             [toggle_btn("digest", "Ежедневная сводка", "📰")], *back()])


def screen_send(draft=None):
    if draft is None:
        return ("📣 <b>Новое сообщение для всех</b>\n\nПришлите текст. Он появится в чате «Yologram» у каждого, "
                "кто откроет клиент (до 15 минут)."), [[btn("Отмена", "menu", "✖️")]]
    reach = stats_counts()["d30"]
    return (f"📣 <b>Предпросмотр</b>\n\n{esc(draft)}\n\n"
            f"Получат примерно <b>{reach}</b> активных за 30 дней."), [
        [btn("Отправить всем", "sendok", "✅", "success")],
        [btn("Изменить", "send", "✏️"), btn("Отмена", "menu", "✖️", "danger")]]


# ---------------------------------------------------------------- actions
def do_export(chat):
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["telegram_id", "last_seen_utc", "badge", "has_banner"])
    for uid, seen, badge, bts in qa("select id, seen, badge, banner_ts from users where seen>0 order by seen desc"):
        w.writerow([uid, time.strftime("%Y-%m-%d %H:%M", time.gmtime(seen)), badge, 1 if bts else 0])
    send_document(chat, "yologram-users.csv", buf.getvalue().encode("utf8"), "📤 Пользователи клиента")


def publish_notice(text):
    ex("insert into notices(ts, text) values(?,?)", int(time.time()), text.strip()[:3500])
    return q1("select max(id) from notices")


def track_new_users():
    """Remembers who we have seen so far; returns the ids that appeared since the last call."""
    known = {r[0] for r in qa("select id from known_users")}
    now = int(time.time())
    fresh = [r[0] for r in qa("select id from users where seen>0") if r[0] not in known]
    first_run = not known
    for uid in fresh:
        ex("insert or ignore into known_users(id, first_seen) values(?,?)", uid, 0 if first_run else now)
    return [] if first_run else fresh


def check_alerts():
    if setting("alert_new") == "1":
        pass  # new users are collected below regardless, so the counters stay right
    fresh = track_new_users()
    if fresh and setting("alert_new") == "1":
        for a in ADMINS:
            tg("sendMessage", chat_id=a, **send_params(f"🆕 <b>Новый пользователь Yologram</b>\nВсего в клиенте: <b>{stats_counts()['total']}</b>", None))
    if setting("alert_crash") == "1":
        for kind, sig, cnt, users, last in crash_rows():
            if kind == "crash" and not qa("select 1 from known_crashes where sig=?", sig):
                ex("insert or ignore into known_crashes(sig, ts) values(?,?)", sig, int(time.time()))
                for a in ADMINS:
                    tg("sendMessage", chat_id=a, **send_params(f"💥 <b>Новый краш</b>\n<code>{esc(sig[:150])}</code>", [[btn("Открыть", "crash", "🐞")]]))
    elif not qa("select 1 from known_crashes limit 1"):
        for _, sig, *_ in crash_rows():  # silence the history when alerts get switched on
            ex("insert or ignore into known_crashes(sig, ts) values(?,?)", sig, int(time.time()))


def maybe_digest():
    if setting("digest") != "1":
        return
    now = time.gmtime(int(time.time()) + TZ_OFFSET * 3600)
    key = time.strftime("%Y-%m-%d", now)
    if now.tm_hour < DIGEST_HOUR or setting("digest_sent", "") == key:
        return
    set_setting("digest_sent", key)
    text, kb = screen_stats()
    for a in ADMINS:
        tg("sendMessage", chat_id=a, **send_params("📰 <b>Ежедневная сводка</b>\n\n" + text.split("\n", 2)[2], kb))


# ---------------------------------------------------------------- dispatch
def on_callback(cb):
    uid = cb["from"]["id"]
    chat = cb["message"]["chat"]["id"]
    mid = cb["message"]["message_id"]
    data = cb.get("data", "")
    answer = ""
    if uid not in ADMINS:
        tg("answerCallbackQuery", callback_query_id=cb["id"], text="Нет доступа", show_alert=True)
        return
    kind, _, arg = data.partition(":")
    STATE.pop(uid, None)
    if kind == "menu":
        text, kb = screen_menu()
    elif kind == "stats":
        text, kb = screen_stats()
    elif kind == "active":
        text, kb = screen_active()
    elif kind == "hist":
        text, kb = screen_hist()
    elif kind == "vers":
        text, kb = screen_vers()
    elif kind == "dev":
        text, kb = screen_dev()
    elif kind == "crash":
        text, kb = screen_crash()
    elif kind == "tr":
        text, kb = screen_trace(int(arg))
    elif kind == "feat":
        text, kb = screen_feat()
    elif kind == "user":
        STATE[uid] = {"await": "user"}
        text, kb = screen_user()
    elif kind == "upd":
        text, kb = screen_upd()
    elif kind == "notes":
        STATE[uid] = {"await": "notes"}
        text, kb = "✏️ <b>Описание обновления</b>\n\nПришлите новый текст, он попадёт в уведомление «Что нового».", [[btn("Отмена", "upd", "✖️")]]
    elif kind == "srv":
        text, kb = screen_srv()
    elif kind == "notif":
        text, kb = screen_notif()
    elif kind == "tg":
        set_setting(arg, "0" if setting(arg) == "1" else "1")
        text, kb = screen_notif()
        answer = "Готово"
    elif kind == "send":
        STATE[uid] = {"await": "notice"}
        text, kb = screen_send()
    elif kind == "sendok":
        draft = cb_draft.pop(uid, None)
        if not draft:
            text, kb = screen_menu()
            answer = "Черновик пуст"
        else:
            nid = publish_notice(draft)
            text, kb = f"✅ <b>Сообщение #{nid} отправлено</b>\n\nПользователи увидят его в чате «Yologram» при ближайшем открытии клиента.", back([btn("История", "hist", "🗂")])
            answer = "Отправлено"
    elif kind == "del":
        ex("delete from notices where id=?", int(arg))
        text, kb = screen_hist()
        answer = "Удалено"
    elif kind == "badge":
        u, v = arg.split(":")
        ex("update users set badge=? where id=?", int(v), int(u))
        text, kb = screen_user(int(u))
        answer = "Готово"
    elif kind == "clr":
        what, u = arg.split(":")
        if what == "t":
            ex("update users set thought=null, thought_ts=0 where id=?", int(u))
        else:
            try:
                os.remove(os.path.join(os.path.dirname(DB), "banners", f"{int(u)}.jpg"))
            except OSError:
                pass
            ex("update users set banner_ts=0 where id=?", int(u))
        text, kb = screen_user(int(u))
        answer = "Готово"
    elif kind == "export":
        do_export(chat)
        tg("answerCallbackQuery", callback_query_id=cb["id"], text="Файл отправлен")
        return
    else:
        text, kb = screen_menu()
    tg("answerCallbackQuery", callback_query_id=cb["id"], **({"text": answer} if answer else {}))
    show(chat, mid, text, kb)


cb_draft = {}


def on_message(m):
    chat = m["chat"]["id"]
    uid = m.get("from", {}).get("id", 0)
    text = (m.get("text") or "").strip()
    if chat < 0:
        return
    if text.startswith("/id") or uid not in ADMINS:
        extra = "" if uid in ADMINS else "\n\nДоступ закрыт. Чтобы стать администратором, добавьте этот ID в ADMIN_IDS на сервере."
        tg("sendMessage", chat_id=chat, **send_params(f"🪪 Ваш Telegram ID: <code>{uid}</code>{extra}", None))
        return
    st = STATE.get(uid, {})
    if text.startswith("/"):
        STATE.pop(uid, None)
        body, kb = screen_menu()
        tg("sendMessage", chat_id=chat, **send_params(body, kb))
        return
    wait = st.get("await")
    if wait == "notice" and text:
        cb_draft[uid] = text
        STATE.pop(uid, None)
        body, kb = screen_send(text)
        tg("sendMessage", chat_id=chat, **send_params(body, kb))
    elif wait == "user" and text.lstrip("-").isdigit():
        STATE.pop(uid, None)
        body, kb = screen_user(int(text))
        tg("sendMessage", chat_id=chat, **send_params(body, kb))
    elif wait == "notes" and text:
        STATE.pop(uid, None)
        p = os.path.join(UPDATES, "latest.json")
        j = latest_json()
        if j:
            j["notes"] = text[:1500]
            with open(p, "w", encoding="utf8") as f:
                json.dump(j, f, ensure_ascii=False)
        body, kb = screen_upd()
        tg("sendMessage", chat_id=chat, **send_params("✅ Описание обновлено.\n\n" + body, kb))
    else:
        body, kb = screen_menu()
        tg("sendMessage", chat_id=chat, **send_params(body, kb))


def main():
    if not TOKEN:
        raise SystemExit("BOT_TOKEN is not set")
    setup()
    load_emoji_pack()
    track_new_users()
    tg("setMyCommands", commands=[{"command": "start", "description": "Панель управления"}, {"command": "id", "description": "Мой Telegram ID"}])
    offset = 0
    last_tick = 0
    print("Yologram bot is running; admins:", sorted(ADMINS) or "none (id lookup only)")
    while True:
        r = tg("getUpdates", offset=offset, timeout=30, allowed_updates=["message", "callback_query"])
        if not r.get("ok"):
            time.sleep(3)
            continue
        for u in r["result"]:
            offset = u["update_id"] + 1
            try:
                if "callback_query" in u:
                    on_callback(u["callback_query"])
                elif "message" in u:
                    on_message(u["message"])
            except Exception:
                traceback.print_exc()
        if time.time() - last_tick > 60:
            last_tick = time.time()
            try:
                check_alerts()
                maybe_digest()
            except Exception:
                traceback.print_exc()


if __name__ == "__main__":
    main()
