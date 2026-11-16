"""Общее состояние бота (живёт в памяти процесса)."""
import os

from db import DB

db = DB(os.environ.get("DB_PATH", "anonbot.sqlite3"))
bot = None  # выставляется в bot.py

partner: dict[int, int] = {}      # uid -> uid собеседника
queue: dict[int, float] = {}      # uid -> время входа в очередь
last: dict[int, int] = {}         # с кем говорил в последний раз (не соединяем сразу снова)
pending_call: dict[int, tuple] = {}   # callee -> (caller, video)
pending_game: dict[int, tuple] = {}   # приглашённый -> (пригласивший, game)

BTN_SEARCH = "🔍 Искать"
BTN_STOP_SEARCH = "⛔ Остановить поиск"
BTN_NEXT = "⏭ Следующий"
BTN_END = "⛔ Завершить"
BTN_CALL = "📞 Звонок"
BTN_VIDEO = "🎥 Видео"
BTN_GAMES = "🎮 Игры"
BTN_SETTINGS = "⚙️ Настройки"
