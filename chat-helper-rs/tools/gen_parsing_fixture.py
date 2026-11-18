"""Записывает поведение Python-оригинала bot/parsing.py на корпусе фраз -> tests/fixtures/parsing.json.
Запуск: python tools/gen_parsing_fixture.py <путь к корню python-бота> > tests/fixtures/parsing.json"""
import itertools, json, random, sys
sys.path.insert(0, sys.argv[1])
from bot.parsing import parse_duration, parse_mod_command, parse_stats_command, parse_period

names = ["бан", "Бан", "разбан", "мут", "размут", "кик", "варн", "варны", "снять варн", "снять  варн", "удалить",
         "закреп", "открепить", "чистка", "пург", "!пург", "-смс", "банка", "бананы", "/ban", "/mute@bot", "/purge", "/start", "/unwarn"]
args = ["", "@User", "@a_b", "123456", "1234", "2ч", "30м", "1д", "1н", "0м", "999999д", "на час", "на", "на неделю",
        "один день", "два часа", "10000 минут", "5 минут", "навсегда", "перманентно", "причина: спам", "причина флуд тут",
        "@User 2ч причина: тест", "123456 навсегда", "50", "0", "абв", "на 3 дня", "и всё", "2 недели", "пять дней",
        "² часа", "٣ч", "@ИВАН 1д", "ПРИЧИНА: Х", "причина:", "  ", "Мут  Навсегда"]
random.seed(7)
texts = []
for n, a in itertools.product(names, args):
    texts += [f"{n} {a}".strip() if a else n, f"{n}  {a}  ".upper() if a and random.random() < .1 else f"{n} {a}"]
texts += ["", "   ", "привет", "стата", "Стата день", "стата неделю", "статистика месяц", "стата вся", "стата 12 часов",
          "стата 169ч", "стата 0ч", "стата 5ч", "стата абв", "статистика", "статистикаx", "стата  3 ч"]
texts += ["30м", "2ч", "1д", "1н", "0м", "", "абв", "99999999999999999999д", "7д", "2 недели", "на месяц", "на час", "٣ч"]
texts = sorted(set(texts))

def sp(d):
    return None if d is None else round(d.total_seconds() * 1_000_000)

out = []
for t in texts:
    c = parse_mod_command(t)
    s = parse_stats_command(t)
    out.append({
        "text": t,
        "duration": sp(parse_duration(t)),
        "period": sp(parse_period(t)),
        "mod": None if c is None else {"name": c.name, "target": c.target, "duration": sp(c.duration), "reason": c.reason},
        "stats": None if s is None else [s.kind, s.hours],
    })
json.dump(out, sys.stdout, ensure_ascii=False, indent=0)
