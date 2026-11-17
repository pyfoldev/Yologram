#!/usr/bin/env python3
"""Nullgram profile sync: stores each user's banner photo and 24h "thought" so other Nullgram users can see them.
Stdlib only. Run:  python3 nullgram_server.py   (listens on 0.0.0.0:8787, data in ./data)
Ownership: the first client to write for a Telegram user id gets a secret token; later writes need that token."""
import json, mimetypes, os, re, secrets, sqlite3, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8787"))
DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
os.makedirs(os.path.join(DATA, "banners"), exist_ok=True)
db = sqlite3.connect(os.path.join(DATA, "db.sqlite"), check_same_thread=False)
db.execute("create table if not exists users(id integer primary key, token text, thought text, thought_ts integer, banner_ts integer)")
db.execute("create table if not exists diag(id integer primary key autoincrement, ts integer, install text, kind text, version text, device text, sdk integer, sig text, thread text, trace text, features text, lang text)")
db.execute("create index if not exists diag_kind on diag(kind, ts)")
db.execute("create table if not exists notices(id integer primary key autoincrement, ts integer, text text)")
for col in ("seen integer default 0", "badge integer default 1"):
    try:
        db.execute("alter table users add column " + col)
    except sqlite3.OperationalError:
        pass
db.commit()
WEB = os.environ.get("YOLOGRAM_WEB", os.path.join(os.path.dirname(os.path.abspath(__file__)), "web"))
mimetypes.add_type("application/wasm", ".wasm")
mimetypes.add_type("text/javascript", ".mjs")
mimetypes.add_type("application/manifest+json", ".webmanifest")
PLUGINS = os.environ.get("NULLGRAM_PLUGINS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "plugins"))
UPDATES = os.environ.get("NULLGRAM_UPDATES", os.path.join(os.path.dirname(os.path.abspath(__file__)), "updates"))
os.makedirs(UPDATES, exist_ok=True)
ADMIN_KEY = os.environ.get("NULLGRAM_ADMIN_KEY", "")
_rate = {}
MAX_BANNER = 1_500_000
LIFETIME = 24 * 3600

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body=b"", ctype="application/json"):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def uid(self):
        m = re.match(r"^/u/(\d{1,15})(/banner\.jpg|/banner|/thought|/hello)?$", self.path.split("?")[0])
        return (int(m.group(1)), m.group(2) or "") if m else (None, None)

    def update(self, path):
        if path == "/update/latest.json":
            p = os.path.join(UPDATES, "latest.json")
            if not os.path.exists(p): return self.send(404)
            return self.send(200, open(p, "rb").read())
        if path == "/update/app.apk":
            p = os.path.join(UPDATES, "app.apk")
            if not os.path.exists(p): return self.send(404)
            size = os.path.getsize(p)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(p, "rb") as f:
                while True:
                    chunk = f.read(262144)
                    if not chunk: break
                    self.wfile.write(chunk)
            return
        self.send(404)

    def plugins(self, path):
        name = path[len("/plugins/"):]
        if not re.match(r"^[A-Za-z0-9_\-]{1,64}\.py$|^index\.json$", name): return self.send(404)
        p = os.path.join(PLUGINS, name)
        if not os.path.isfile(p): return self.send(404)
        self.send(200, open(p, "rb").read(), "application/json" if name.endswith(".json") else "text/x-python; charset=utf-8")

    def static(self, path):
        # the Yologram Web client lives in WEB; anything that is not an API route is looked up there
        rel = path.lstrip("/") or "index.html"
        full = os.path.realpath(os.path.join(WEB, rel))
        root = os.path.realpath(WEB)
        if not (full == root or full.startswith(root + os.sep)) or not os.path.isfile(full):
            return self.send(404)
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        size = os.path.getsize(full)
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        with open(full, "rb") as f:
            while True:
                chunk = f.read(262144)
                if not chunk: break
                self.wfile.write(chunk)

    def do_GET(self):
        path0 = self.path.split("?")[0]
        if path0.startswith("/update/"): return self.update(path0)
        if path0.startswith("/plugins/"): return self.plugins(path0)
        if path0 == "/diag/admin": return self.admin()
        if path0 == "/notices": return self.notices()
        uid, sub = self.uid()
        if uid is None: return self.static(path0)
        row = db.execute("select thought, thought_ts, banner_ts from users where id=?", (uid,)).fetchone()
        if sub == "/banner.jpg":
            p = os.path.join(DATA, "banners", f"{uid}.jpg")
            if not os.path.exists(p): return self.send(404)
            return self.send(200, open(p, "rb").read(), "image/jpeg")
        if not row: return self.send(200, {"thought": None, "banner_ts": 0})
        thought, ts, bts = row
        if thought and time.time() - (ts or 0) >= LIFETIME: thought = None
        self.send(200, {"thought": thought, "thought_ts": ts or 0, "banner_ts": bts or 0})

    def notices(self):
        # Service messages for the "Yologram" chat. ?since=N -> everything newer than id N; ?latest=1 -> only the newest id.
        from urllib.parse import urlparse, parse_qs
        qs = parse_qs(urlparse(self.path).query)
        if qs.get("latest"):
            row = db.execute("select coalesce(max(id),0) from notices").fetchone()
            return self.send(200, {"latest": row[0]})
        try:
            since = int(qs.get("since", ["0"])[0])
        except ValueError:
            since = 0
        rows = db.execute("select id, ts, text from notices where id>? order by id limit 20", (since,)).fetchall()
        self.send(200, {"notices": [{"id": r[0], "ts": r[1], "text": r[2]} for r in rows]})

    def badges(self):
        n = int(self.headers.get("Content-Length", "0"))
        if n > 8192: return self.send(413)
        try:
            ids = [int(x) for x in json.loads(self.rfile.read(n)).get("ids", [])][:300]
        except Exception:
            return self.send(400)
        if not ids: return self.send(200, {"ids": []})
        since = int(time.time()) - 45 * 86400
        marks = ",".join("?" * len(ids))
        rows = db.execute(f"select id from users where badge=1 and seen>? and id in ({marks})", [since] + ids).fetchall()
        self.send(200, {"ids": [r[0] for r in rows]})

    def do_POST(self):
        if self.path.split("?")[0] == "/badges":
            return self.badges()
        if self.path.split("?")[0] != "/diag":
            return self.send(404)
        n = int(self.headers.get("Content-Length", "0"))
        if n > 65536: return self.send(413)
        ip = self.client_address[0]
        now = time.time()
        hits = [t for t in _rate.get(ip, []) if now - t < 3600]
        if len(hits) >= 120: return self.send(429)
        _rate[ip] = hits + [now]
        try:
            o = json.loads(self.rfile.read(n))
            kind = str(o.get("kind", ""))[:12]
            if kind not in ("ping", "crash", "error"): return self.send(400)
            c = lambda k, m=200: str(o.get(k, ""))[:m]
            db.execute("insert into diag(ts,install,kind,version,device,sdk,sig,thread,trace,features,lang) values(?,?,?,?,?,?,?,?,?,?,?)",
                (int(now), c("install", 64), kind, c("version", 32), c("device", 80), int(o.get("sdk", 0) or 0), c("sig", 300), c("thread", 60), c("trace", 6000), json.dumps(o.get("features", {}))[:1000], c("lang", 8)))
            db.commit()
        except Exception:
            return self.send(400)
        self.send(200, {"ok": True})

    def admin(self):
        from urllib.parse import urlparse, parse_qs
        from html import escape
        qs = parse_qs(urlparse(self.path).query)
        if not ADMIN_KEY or not secrets.compare_digest(qs.get("key", [""])[0], ADMIN_KEY): return self.send(403)
        day = int(time.time()) - 86400
        week = int(time.time()) - 7 * 86400
        one = lambda q, *a: db.execute(q, a).fetchone()[0]
        rows = lambda q, *a: db.execute(q, a).fetchall()
        out = ["<meta charset=utf-8><meta name=viewport content='width=device-width'><style>body{font:14px system-ui;margin:16px;max-width:900px}table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ccc;padding:4px 8px;text-align:left;vertical-align:top}pre{white-space:pre-wrap;font-size:12px}</style>"]
        out.append("<h2>Nullgram diagnostics</h2>")
        out.append(f"<p>Installs: {one('select count(distinct install) from diag')} · active 24h: {one('select count(distinct install) from diag where ts>?', day)} · active 7d: {one('select count(distinct install) from diag where ts>?', week)}</p>")
        out.append("<h3>Versions (7d)</h3><table>" + "".join(f"<tr><td>{escape(v)}</td><td>{n}</td></tr>" for v, n in rows("select version, count(distinct install) from diag where ts>? group by version order by 2 desc", week)) + "</table>")
        out.append("<h3>Devices (7d)</h3><table>" + "".join(f"<tr><td>{escape(v)}</td><td>{n}</td></tr>" for v, n in rows("select device, count(distinct install) from diag where ts>? group by device order by 2 desc limit 15", week)) + "</table>")
        out.append("<h3>Problems (7d)</h3><table><tr><th>kind</th><th>count</th><th>users</th><th>last</th><th>where</th></tr>")
        for kind, sig, n, u, last in rows("select kind, sig, count(*), count(distinct install), max(ts) from diag where kind in ('crash','error') and ts>? group by kind, sig order by (kind='crash') desc, 3 desc limit 40", week):
            tr = rows("select trace, version, device from diag where sig=? order by ts desc limit 1", sig)[0]
            out.append(f"<tr><td>{kind}</td><td>{n}</td><td>{u}</td><td>{time.strftime('%m-%d %H:%M', time.localtime(last))}</td><td><details><summary>{escape(sig[:110])}</summary><pre>{escape(tr[0])}</pre><small>{escape(tr[1])} · {escape(tr[2])}</small></details></td></tr>")
        out.append("</table>")
        feats = {}
        for (f,) in rows("select features from diag where kind='ping' and ts>? group by install", week):
            try:
                for k, v in json.loads(f).items():
                    if v is True: feats[k] = feats.get(k, 0) + 1
            except Exception: pass
        out.append("<h3>Features on (7d)</h3><table>" + "".join(f"<tr><td>{escape(k)}</td><td>{v}</td></tr>" for k, v in sorted(feats.items(), key=lambda x: -x[1])) + "</table>")
        self.send(200, "".join(out).encode(), "text/html; charset=utf-8")

    def do_PUT(self):
        uid, sub = self.uid()
        if uid is None or sub not in ("/banner", "/thought", "/hello"): return self.send(404)
        n = int(self.headers.get("Content-Length", "0"))
        if n > MAX_BANNER: return self.send(413)
        body = self.rfile.read(n)
        token = self.headers.get("X-Token", "")
        row = db.execute("select token from users where id=?", (uid,)).fetchone()
        if row is None:
            token = secrets.token_hex(16)
            db.execute("insert into users(id, token) values(?,?)", (uid, token)); db.commit()
            issued = token
        else:
            issued = None
            if not secrets.compare_digest(row[0] or "", token):
                # A reinstalled app (or a second device) has lost its token. A plain "I run Yologram" ping still
                # counts, so the badge keeps working; hiding the badge, thoughts and banners need the owner's token.
                if sub == "/hello" and body.strip() != b"0":
                    db.execute("update users set seen=? where id=?", (int(time.time()), uid)); db.commit()
                    return self.send(200, {"ok": True, "token": None})
                return self.send(403)
        if sub == "/hello":
            show = 0 if body.strip() == b"0" else 1
            db.execute("update users set seen=?, badge=? where id=?", (int(time.time()), show, uid))
        elif sub == "/thought":
            text = body.decode("utf8", "ignore").strip()[:60]
            db.execute("update users set thought=?, thought_ts=? where id=?", (text or None, int(time.time()) if text else 0, uid))
        else:
            if body[:3] != b"\xff\xd8\xff" and n:  # JPEG only
                return self.send(415)
            p = os.path.join(DATA, "banners", f"{uid}.jpg")
            if n: open(p, "wb").write(body)
            elif os.path.exists(p): os.remove(p)
            db.execute("update users set banner_ts=? where id=?", (int(time.time()) if n else 0, uid))
        db.commit()
        self.send(200, {"ok": True, "token": issued})

ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
