from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass
class Config:
    bot_token: str
    db_path: str = "data/nori.db"


def load_config() -> Config:
    if os.path.exists(".env"):
        with open(".env", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip())
    token = os.environ.get("BOT_TOKEN", "")
    if not token:
        raise SystemExit("BOT_TOKEN не задан (см. .env.example)")
    return Config(bot_token=token, db_path=os.environ.get("DB_PATH", "data/nori.db"))
