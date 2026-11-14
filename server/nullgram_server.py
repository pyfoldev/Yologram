#!/usr/bin/env python3
"""Nullgram profile sync: stores each user's banner photo and 24h "thought" so other Nullgram users can see them.
Stdlib only. Run:  python3 nullgram_server.py   (listens on 0.0.0.0:8787, data in ./data)
Ownership: the first client to write for a Telegram user id gets a secret token; later writes need that token."""
import json, os, re, secrets, sqlite3, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8787"))
DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
os.makedirs(os.path.join(DATA, "banners"), exist_ok=True)
db = sqlite3.connect(os.path.join(DATA, "db.sqlite"), check_same_thread=False)
db.execute("create table if not exists users(id integer primary key, token text, thought text, thought_ts integer, banner_ts integer)")
db.commit()
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
        m = re.match(r"^/u/(\d{1,15})(/banner\.jpg|/banner|/thought)?$", self.path.split("?")[0])
        return (int(m.group(1)), m.group(2) or "") if m else (None, None)

    def do_GET(self):
        uid, sub = self.uid()
        if uid is None: return self.send(404)
        row = db.execute("select thought, thought_ts, banner_ts from users where id=?", (uid,)).fetchone()
        if sub == "/banner.jpg":
            p = os.path.join(DATA, "banners", f"{uid}.jpg")
            if not os.path.exists(p): return self.send(404)
            return self.send(200, open(p, "rb").read(), "image/jpeg")
        if not row: return self.send(200, {"thought": None, "banner_ts": 0})
        thought, ts, bts = row
        if thought and time.time() - (ts or 0) >= LIFETIME: thought = None
        self.send(200, {"thought": thought, "thought_ts": ts or 0, "banner_ts": bts or 0})

    def do_PUT(self):
        uid, sub = self.uid()
        if uid is None or sub not in ("/banner", "/thought"): return self.send(404)
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
            if not secrets.compare_digest(row[0] or "", token): return self.send(403)
            issued = None
        if sub == "/thought":
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
