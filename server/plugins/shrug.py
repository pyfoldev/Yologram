NAME = "Шраг и эмоции"
VERSION = "1.0"
DESCRIPTION = "Команды /shrug, /tableflip и /unflip заменяются на смайлы"
AUTHOR = "pyfold"

REPLACEMENTS = {
    "/shrug": "¯\\_(ツ)_/¯",
    "/tableflip": "(╯°□°)╯︵ ┻━┻",
    "/unflip": "┬─┬ ノ( ゜-゜ノ)",
}


def on_send(api, dialog_id, text):
    stripped = text.strip()
    if stripped in REPLACEMENTS:
        return REPLACEMENTS[stripped]
    for cmd, face in REPLACEMENTS.items():
        if stripped.endswith(" " + cmd):
            return stripped[: -len(cmd)] + face
    return None
