"""Yologram plugin engine (Python side).

A plugin is a single .py file. Optional module-level names: NAME, VERSION, DESCRIPTION, AUTHOR.
Optional hooks (all receive `api` first):
    on_load(api)
    on_message(api, msg)            incoming message: dict(id, dialog_id, from_id, text, out)
    on_send(api, dialog_id, text)   return a new text to change the message, "" to cancel it, None to keep it
`api` offers: toast(text), send_message(dialog_id, text), log(text), my_id(), copy(text), get(key, default), set(key, value)
"""
import importlib.util
import os
import traceback

_plugins = {}


def load(path, api):
    """Loads one plugin file; returns [name, version, description] or raises."""
    name = os.path.splitext(os.path.basename(path))[0]
    spec = importlib.util.spec_from_file_location("yologram_plugin_" + name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    _plugins[name] = mod
    hook = getattr(mod, "on_load", None)
    if hook:
        hook(api)
    return [getattr(mod, "NAME", name), str(getattr(mod, "VERSION", "")), str(getattr(mod, "DESCRIPTION", ""))]


def unload(name):
    _plugins.pop(name, None)


def on_message(api, msg):
    for name, mod in list(_plugins.items()):
        hook = getattr(mod, "on_message", None)
        if hook:
            try:
                hook(api, msg)
            except Exception:
                api.log("%s.on_message failed:\n%s" % (name, traceback.format_exc()))


def on_send(api, dialog_id, text):
    """Runs every plugin's on_send in turn; returns the final text, or None to drop the message."""
    for name, mod in list(_plugins.items()):
        hook = getattr(mod, "on_send", None)
        if hook:
            try:
                r = hook(api, dialog_id, text)
            except Exception:
                api.log("%s.on_send failed:\n%s" % (name, traceback.format_exc()))
                continue
            if r is None:
                continue
            if r == "":
                return None
            text = str(r)
    return text
