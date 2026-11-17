import re

NAME = "Аккуратный текст"
VERSION = "1.0"
DESCRIPTION = "Убирает двойные пробелы и пробелы в конце сообщения перед отправкой"
AUTHOR = "pyfold"


def on_send(api, dialog_id, text):
    tidy = re.sub(r"[ \t]{2,}", " ", text).strip()
    if tidy != text and tidy:
        return tidy
    return None
