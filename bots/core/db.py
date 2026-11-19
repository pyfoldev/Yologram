"""Крошечная обёртка над SQLite — хватает для небольших ботов.

SQLite встроена в Python, ставить ничего не нужно. Запросы быстрые, поэтому
вызываем их синхронно прямо из хендлеров. Для нагруженного бота замените
этот класс на aiosqlite / PostgreSQL — интерфейс (execute/query/one) останется.
"""
import os
import sqlite3
from pathlib import Path
from typing import Any, Iterable

# Папка с базами. Можно переопределить переменной BOTS_DATA_DIR (так делают тесты).
DATA_DIR = Path(os.getenv("BOTS_DATA_DIR") or Path(__file__).resolve().parent.parent / "data")


class Database:
    def __init__(self, name: str, schema: str = "", path: str | None = None):
        """name — имя файла БД (data/<name>.db); schema — SQL создания таблиц."""
        if path is None:
            DATA_DIR.mkdir(parents=True, exist_ok=True)
            path = str(DATA_DIR / f"{name}.db")
        self.conn = sqlite3.connect(path)
        self.conn.row_factory = sqlite3.Row  # строки доступны как row["поле"]
        if schema:
            self.conn.executescript(schema)

    def execute(self, sql: str, params: Iterable[Any] = ()) -> int:
        """INSERT/UPDATE/DELETE. Возвращает id вставленной строки."""
        cur = self.conn.execute(sql, tuple(params))
        self.conn.commit()
        return cur.lastrowid or 0

    def query(self, sql: str, params: Iterable[Any] = ()) -> list[sqlite3.Row]:
        """SELECT, много строк."""
        return self.conn.execute(sql, tuple(params)).fetchall()

    def one(self, sql: str, params: Iterable[Any] = ()) -> sqlite3.Row | None:
        """SELECT, одна строка (или None)."""
        return self.conn.execute(sql, tuple(params)).fetchone()
