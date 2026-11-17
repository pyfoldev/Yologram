"""
Text Tools — плагин для nullgram / yologram.

Команды (пиши в поле ввода и отправляй):
  .up текст      -> ТЕКСТ
  .low ТЕКСТ     -> текст
  .mock текст    -> tEkСт в сТиЛе мОкИнГа
  .flip текст    -> текст вверх ногами
  .rev текст     -> тскет (наоборот)
  .shrug         -> ¯\\_(ツ)_/¯
  .stats         -> сколько сообщений отправлено через плагин

Хуки: on_load, on_message, on_send
API:  toast, send_message, copy, my_id, get, set, log
"""

NAME = "Text Tools"
VERSION = "1.0"

_FLIP = dict(zip(
    "abcdefghijklmnopqrstuvwxyz",
    "ɐqɔpǝɟƃɥᴉɾʞlɯuodbɹsʇnʌʍxʎz",
))
_FLIP.update({
    "?": "¿", "!": "¡", ".": "˙", ",": "'", "(": ")", ")": "(",
    "[": "]", "]": "[", "<": ">", ">": "<", "_": "‾",
})


def _api(name):
    """Достаём функцию API независимо от того, как её отдаёт рантайм."""
    fn = globals().get(name)
    return fn if callable(fn) else None


def _call(name, *args):
    fn = _api(name)
    if fn is None:
        return None
    try:
        return fn(*args)
    except Exception as e:  # плагин не должен ронять клиент
        _log("API %s error: %s" % (name, e))
        return None


def _log(msg):
    fn = _api("log")
    if fn:
        try:
            fn("[TextTools] " + str(msg))
        except Exception:
            pass


def _mock(s):
    out, up = [], False
    for ch in s:
        if ch.isalpha():
            out.append(ch.upper() if up else ch.lower())
            up = not up
        else:
            out.append(ch)
    return "".join(out)


def _flip(s):
    return "".join(_FLIP.get(c, c) for c in reversed(s.lower()))


COMMANDS = {
    ".up": lambda s: s.upper(),
    ".low": lambda s: s.lower(),
    ".mock": _mock,
    ".flip": _flip,
    ".rev": lambda s: s[::-1],
}


def _bump_counter():
    try:
        n = int(_call("get", "sent_count") or 0) + 1
        _call("set", "sent_count", str(n))
    except Exception:
        pass


# ---------- хуки ----------

def on_load():
    _call("toast", "%s %s загружен" % (NAME, VERSION))
    _log("loaded")


def on_message(*args, **kwargs):
    """Входящие сообщения. Здесь ничего не меняем, только логируем."""
    try:
        text = args[0] if args else kwargs.get("text", "")
        if isinstance(text, str) and text:
            _log("incoming: %s" % text[:50])
    except Exception:
        pass


def on_send(text, *args, **kwargs):
    """Исходящие: обрабатываем команды. Возвращаем новый текст."""
    if not isinstance(text, str):
        return text

    stripped = text.strip()

    if stripped == ".shrug":
        _bump_counter()
        return "¯\\_(ツ)_/¯"

    if stripped == ".stats":
        n = _call("get", "sent_count") or "0"
        _call("toast", "Отправлено через плагин: %s" % n)
        return ""  # не отправляем саму команду

    head, _, rest = stripped.partition(" ")
    handler = COMMANDS.get(head)
    if handler and rest:
        _bump_counter()
        return handler(rest)

    return text
