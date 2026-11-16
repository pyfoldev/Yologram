"""Оценка даты регистрации аккаунта по Telegram ID.

Telegram не отдаёт дату регистрации через API. ID выдаются почти по возрастанию,
поэтому дату можно оценить интерполяцией между известными «опорными» точками
(ID -> дата). Чем плотнее точки, тем точнее. Файл anchors.json (опционально)
дополняет встроенные точки — его пополняет админ командой /anchor.
"""
from __future__ import annotations

import bisect
import json
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

ANCHORS_FILE = Path(__file__).with_name("anchors.json")

# (user_id, unix_time). Ранние точки — по публичным оценкам, поздние приблизительные.
BUILTIN = [
    (2768409, 1383264000), (7679610, 1388448000), (11538514, 1391212000),
    (15835244, 1392940000), (23646077, 1393459000), (38015510, 1393632000),
    (44634663, 1399334000), (46145305, 1400198000), (54845238, 1411257000),
    (63263518, 1414454000), (101260938, 1425600000), (111220210, 1429574000),
    (116812045, 1437696000), (120372048, 1437782000), (133909606, 1444003200),
    (152079341, 1453593600), (171295414, 1457913600), (181783990, 1461628800),
    (222021233, 1470700800), (278941742, 1482000000), (285253072, 1485043200),
    (294851037, 1488844800), (328594461, 1494979200), (337808429, 1498608000),
    (369669043, 1508112000), (400169472, 1515283200), (500000000, 1530403200),
    (700000000, 1554000000), (1000000000, 1580000000), (1500000000, 1596000000),
    (2000000000, 1608000000), (5000000000, 1620000000), (5500000000, 1631000000),
    (6000000000, 1640000000), (6500000000, 1664000000), (7000000000, 1695000000),
    (7500000000, 1717000000), (8000000000, 1735000000),
]


def _load() -> list[tuple[int, int]]:
    pts = dict(BUILTIN)
    try:
        for k, v in json.loads(ANCHORS_FILE.read_text()).items():
            pts[int(k)] = int(v)
    except (OSError, ValueError):
        pass
    out: list[tuple[int, int]] = []
    for uid, ts in sorted(pts.items()):
        if not out or ts >= out[-1][1]:  # дата не должна убывать
            out.append((uid, ts))
    return out


def add_anchor(uid: int, ts: int) -> None:
    try:
        data = json.loads(ANCHORS_FILE.read_text())
    except (OSError, ValueError):
        data = {}
    data[str(uid)] = ts
    ANCHORS_FILE.write_text(json.dumps(data, indent=1))


@dataclass
class RegEstimate:
    date: datetime
    err_days: int      # ± погрешность в днях (грубо)
    approx_kind: str   # "interpolation" | "extrapolation"

    def human(self) -> str:
        months = ["янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"]
        d = self.date
        if self.err_days <= 45:
            return f"{months[d.month - 1]} {d.year}"
        return f"{months[d.month - 1]} {d.year} (±{round(self.err_days / 30)} мес.)"


def estimate(uid: int) -> RegEstimate:
    pts = _load()
    ids = [p[0] for p in pts]
    i = bisect.bisect_left(ids, uid)
    kind = "interpolation"
    if i == 0:
        (a, ta), (b, tb) = pts[0], pts[1]
        kind = "extrapolation"
    elif i >= len(pts):
        (a, ta), (b, tb) = pts[-2], pts[-1]
        kind = "extrapolation"
    else:
        (a, ta), (b, tb) = pts[i - 1], pts[i]
        if a == uid:
            return RegEstimate(datetime.fromtimestamp(ta, timezone.utc), 15, kind)
    ts = ta + (uid - a) * (tb - ta) / (b - a)
    ts = min(ts, time.time())
    err = max(15, int((tb - ta) / 86400 * 0.25))
    if kind == "extrapolation":
        err *= 3
    return RegEstimate(datetime.fromtimestamp(ts, timezone.utc), err, kind)
