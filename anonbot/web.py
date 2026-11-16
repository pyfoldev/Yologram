"""Веб-часть: страница звонка + WebRTC-сигналинг по WebSocket."""
import os
import secrets
import time
from pathlib import Path

from aiohttp import WSMsgType, web

STATIC = Path(__file__).parent / "static"
rooms: dict[str, dict] = {}
ROOM_TTL = 6 * 3600


def create_room(video: bool, pair: frozenset) -> str:
    now = time.time()
    for rid in [r for r, v in rooms.items() if now - v["ts"] > ROOM_TTL]:
        rooms.pop(rid, None)
    rid = secrets.token_urlsafe(12)
    rooms[rid] = {"video": video, "peers": [], "pair": pair, "ts": now}
    return rid


async def close_pair(pair: frozenset):
    for rid, r in list(rooms.items()):
        if r["pair"] == pair:
            rooms.pop(rid, None)
            for ws in list(r["peers"]):
                try:
                    await ws.send_json({"type": "ended"})
                    await ws.close()
                except Exception:
                    pass


def ice_servers():
    servers = [{"urls": [os.environ.get("STUN_URL", "stun:stun.l.google.com:19302")]}]
    if os.environ.get("TURN_URL"):
        servers.append({
            "urls": [os.environ["TURN_URL"]],
            "username": os.environ.get("TURN_USER", ""),
            "credential": os.environ.get("TURN_PASS", ""),
        })
    return servers


@web.middleware
async def headers(request, handler):
    resp = await handler(request)
    resp.headers["Referrer-Policy"] = "no-referrer"
    resp.headers["Cache-Control"] = "no-store"
    resp.headers["X-Robots-Tag"] = "noindex"
    return resp


async def page(request):
    return web.FileResponse(STATIC / "call.html")


async def api_room(request):
    r = rooms.get(request.match_info["room"])
    if not r:
        return web.json_response({"error": "Звонок не найден или уже завершён"}, status=404)
    return web.json_response({
        "video": r["video"],
        "iceServers": ice_servers(),
        "relay": os.environ.get("FORCE_RELAY") == "1" and bool(os.environ.get("TURN_URL")),
    })


async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=20)
    await ws.prepare(request)
    r = rooms.get(request.match_info["room"])
    if not r:
        await ws.send_json({"type": "ended"})
        await ws.close()
        return ws
    if len(r["peers"]) >= 2:
        await ws.send_json({"type": "full"})
        await ws.close()
        return ws
    r["peers"].append(ws)
    if len(r["peers"]) == 2:
        await r["peers"][0].send_json({"type": "ready"})  # первый создаёт offer
    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            for other in r["peers"]:
                if other is not ws and not other.closed:
                    await other.send_str(msg.data)
    finally:
        if ws in r["peers"]:
            r["peers"].remove(ws)
        for other in r["peers"]:
            if not other.closed:
                await other.send_json({"type": "left"})
    return ws


async def start(host: str, port: int):
    app = web.Application(middlewares=[headers])
    app.add_routes([
        web.get("/", lambda r: web.Response(text="ok")),
        web.get("/call/{room}", page),
        web.get("/api/room/{room}", api_room),
        web.get("/ws/{room}", ws_handler),
    ])
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, host, port).start()
    return runner
