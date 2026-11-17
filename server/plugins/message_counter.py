NAME = "Счётчик сообщений"
VERSION = "1.0"
DESCRIPTION = "Считает входящие сообщения; при запуске показывает, сколько их было"
AUTHOR = "pyfold"


def on_load(api):
    total = int(api.get("total", 0) or 0)
    api.toast("Получено сообщений: %d" % total)


def on_message(api, msg):
    if msg.get("out"):
        return
    api.set("total", int(api.get("total", 0) or 0) + 1)
