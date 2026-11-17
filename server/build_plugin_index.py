#!/usr/bin/env python3
"""Rebuilds plugins/index.json (name, version, description, author, sha256) from the NAME/VERSION/DESCRIPTION/AUTHOR constants of every .py file in plugins/.
Run it after adding or editing a plugin:  python3 build_plugin_index.py"""
import ast, hashlib, json, os

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "plugins")
entries = []
for file in sorted(os.listdir(HERE)):
    if not file.endswith(".py"):
        continue
    raw = open(os.path.join(HERE, file), "rb").read()
    consts = {}
    for node in ast.parse(raw.decode("utf-8")).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name) and isinstance(node.value, ast.Constant):
            consts[node.targets[0].id] = str(node.value.value)
    entries.append({
        "file": file,
        "name": consts.get("NAME", file[:-3]),
        "version": consts.get("VERSION", ""),
        "description": consts.get("DESCRIPTION", ""),
        "author": consts.get("AUTHOR", ""),
        "sha256": hashlib.sha256(raw).hexdigest(),
    })
with open(os.path.join(HERE, "index.json"), "w", encoding="utf-8") as out:
    json.dump({"plugins": entries}, out, ensure_ascii=False, indent=2)
print(len(entries), "plugins indexed")
