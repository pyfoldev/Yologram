import sqlite3

FIELDS = {"gender", "want", "interests", "by_interests"}


class DB:
    def __init__(self, path: str):
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute(
            """CREATE TABLE IF NOT EXISTS users(
                id INTEGER PRIMARY KEY,
                gender TEXT,                       -- m / f / NULL
                want TEXT NOT NULL DEFAULT 'any',  -- any / m / f
                interests TEXT NOT NULL DEFAULT '',
                by_interests INTEGER NOT NULL DEFAULT 1
            )"""
        )
        self.conn.commit()

    def get(self, uid: int) -> dict:
        self.conn.execute("INSERT OR IGNORE INTO users(id) VALUES(?)", (uid,))
        row = self.conn.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
        d = dict(row)
        d["interests"] = {x for x in d["interests"].split(",") if x}
        d["by_interests"] = bool(d["by_interests"])
        return d

    def set(self, uid: int, **kw):
        self.get(uid)
        for k, v in kw.items():
            if k not in FIELDS:
                raise ValueError(k)
            if k == "interests":
                v = ",".join(sorted(v))
            if k == "by_interests":
                v = int(v)
            self.conn.execute(f"UPDATE users SET {k}=? WHERE id=?", (v, uid))
        self.conn.commit()
