"""Собирает nori/emoji_map.json из набора https://t.me/addemoji/solutions_project_by_Solutmark_bot.

tools/packs/solutions.json — снимок набора: пары [эмодзи, custom_emoji_id] (Bot API getStickerSet:
sticker.emoji и sticker.custom_emoji_id). Запуск из корня: python tools/build_emoji_map.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
norm = lambda e: e.replace("️", "")

out: dict[str, str] = {}
for emoji, cid in json.loads((ROOT / "packs" / "solutions.json").read_text(encoding="utf-8")):
    if emoji and cid:
        out.setdefault(norm(emoji), cid)
(ROOT.parent / "nori" / "emoji_map.json").write_text(json.dumps(out, ensure_ascii=False, indent=0, sort_keys=True), encoding="utf-8")
print(f"{len(out)} эмодзи в карте")
