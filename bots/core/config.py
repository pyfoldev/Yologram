"""Настройки берутся из переменных окружения (или из файла .env).

Так токены не попадают в код и в git.
"""
import os
from pathlib import Path

from dotenv import load_dotenv

# Ищем .env в папке bots/ (рядом с requirements.txt) и в текущей папке.
load_dotenv(Path(__file__).resolve().parent.parent / ".env")
load_dotenv()


def get_token() -> str:
    """Возвращает токен бота или понятно объясняет, что делать."""
    token = os.getenv("BOT_TOKEN", "").strip()
    if not token:
        raise SystemExit(
            "Не задан BOT_TOKEN. Скопируйте bots/.env.example в bots/.env "
            "и вставьте токен от @BotFather."
        )
    return token


def admin_ids() -> set[int]:
    """Множество ID админов из ADMIN_IDS="1,2,3". Пустое — если не задано."""
    raw = os.getenv("ADMIN_IDS", "")
    return {int(x) for x in raw.replace(" ", "").split(",") if x.isdigit()}


def is_admin(user_id: int | None) -> bool:
    """Проверка прав: используйте в хендлерах `if not is_admin(m.from_user.id): return`."""
    return user_id is not None and user_id in admin_ids()
