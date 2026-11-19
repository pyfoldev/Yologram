"""Общее ядро для всех ботов: конфиг, запуск и база данных."""
from .config import admin_ids, get_token, is_admin
from .db import Database
from .runner import run_bot

__all__ = ["Database", "admin_ids", "get_token", "is_admin", "run_bot"]
