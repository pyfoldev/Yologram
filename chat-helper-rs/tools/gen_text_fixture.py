"""Корпус для filters.py и links.py: python tools/gen_text_fixture.py <корень бота> > tests/fixtures/text.json"""
import json, sys
sys.path.insert(0, sys.argv[1])
from bot.filters import normalize, validate_regex, FilterSet, FilterError
from bot.links import normalize_domain, extract_hosts, host_allowed

texts = ["Привет, Мир!!!", "ПРИВЕЕЕЕТ", "ёжик Ёлка", "xyz abc ХУЙ", "спа́м", "s p a m", "a​b‍c", "ﬁre ① ²", "мама мыла раму", "  ", "",
         "Hello_World--foo", "ТЫ КУПИ 123 ТОВАР", "kykу", "аaааа", "Ⅳ ㎏ ｆｕｌｌ", "élan", "日本語 テキスト", "a.b", "ёё\nё"]
patterns = ["abc", r"abc\d+", "(a+)+", "(?=a)", r"(a)\1", "(", "", "a" * 201, r"(\+)+", "(a|b)*", "(a|b)", "[(a|b)+]", r"\p{L}+", "(?i)x", "a{2,3}", "(ab){2}", "(?P<n>a)", "(?:a|b)+c", "x(?<=y)", "**"]
domains = ["https://www.Example.com/a", "*.x.org", "мусор", "пример.рф", "WWW.Test.RU:8080/path?q", "user@host.com", "a..b.com", ".a.com", "http://a.b.c.example.com/x#y",
           "t.me/abc", "https://t.me/abc", "example", "a b.com", "*.sub.пример.рф", "https://user:pw@site.io:99/p", "x.com.", "(site.org)", "«a.org»"]
msgs = ["смотри t.me/x и https://a.example.com/p, mail@evil.com", "www.foo.ru тут", "вот site.xyz!", "no links", "a.b.com.ru", "x@y.com z.net -q.org .w.io",
        "https://Пример.рф/q", "http://1.2.3.4/x", "go to bit.ly/zzz or foo.top"]
allows = [[], ["a.example.com"], ["*.example.com"], ["example.com"], ["*.com"]]
hosts = ["a.example.com", "example.com", "badexample.com", "x.y.example.com", "com"]

out = {"norm": [[t, normalize(t)] for t in texts], "regex": [], "set": [], "domain": [[d, normalize_domain(d)] for d in domains],
       "hosts": [[m, sorted(extract_hosts(m, []))] for m in msgs], "allowed": []}
for p in patterns:
    try:
        validate_regex(p); out["regex"].append([p, True])
    except FilterError:
        out["regex"].append([p, False])
rows = [{"id": 3, "kind": "word", "pattern": "Спам"}, {"id": 5, "kind": "regex", "pattern": r"купи\s+\d+"}, {"id": 4, "kind": "word", "pattern": "xyz"}, {"id": 9, "kind": "regex", "pattern": "(abc)"}]
fs = FilterSet(rows)
for t in texts + ["это СПААААМ!", "Купи   123", "спамить", "хуz", "ABC"]:
    out["set"].append([t, fs.first_match(t)])
for a in allows:
    for h in hosts:
        out["allowed"].append([a, h, host_allowed(h, set(a))])
json.dump(out, sys.stdout, ensure_ascii=False, indent=0)
