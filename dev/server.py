"""Stand-in for the Android app's native side, to try the page in a desktop browser.

    python dev/server.py [port] [--open]     then open http://localhost:8080
                                             (--open does that for you)

On the tablet the app's Java code serves the page, relays each camera's alarm
stream, proxies snapshots (they need a login a web page cannot give) and keeps
the settings. This script does the same four things on the PC. The camera
login comes from dev/cameras.env (see dev/cameras.example.env; or point
WALLCLOCK_ENV at another file); settings saved from the page are kept in
memory only. The tablet never uses this.

http://localhost:8080/remote shows the remote-control page, with this script
standing in for the tablet: it keeps the settings, and pretends to change view
and to ring when told to (a ring lasts a few seconds). No PIN is asked.
"""

import asyncio
import json
import logging
import mimetypes
import os
import re
import sys
import time
import webbrowser
from pathlib import Path

import httpx
from aiohttp import web
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parent.parent
WEB_DIR = ROOT / "web"
SLIDES_DIR = ROOT / "slides"   # the PC copy of the slideshow pictures
MEDIA_DIR = ROOT / "media"     # the PC copy of what the alarm clock plays
ENV_FILE = Path(os.environ.get("WALLCLOCK_ENV") or ROOT / "dev" / "cameras.env")
PORT = next((int(arg) for arg in sys.argv[1:] if arg.isdigit()), 8080)

logger = logging.getLogger("dev")

# One event per line: "Code=CrossLineDetection;action=Start;index=0;data={...".
_EVENT_RE = re.compile(r"Code=(\w+);action=(\w+);index=(\d+)")
_HEARTBEAT = 5


class Dev:
    def __init__(self):
        env = dotenv_values(ENV_FILE)
        self.user, self.password = env.get("CAM_USER", ""), env.get("CAM_PASS", "")
        self.hosts = [env.get("CAM_HOST", "cam.local"), env.get("CAM_REAR_HOST", "rcam.local")]
        self.saved = None      # settings saved from the page (this run only)
        self.clients = set()   # one queue per open page
        self.view, self.rings_until = "clock", 0.0   # the pretend tablet behind /remote

    def auth(self) -> httpx.DigestAuth:
        return httpx.DigestAuth(self.user, self.password)

    def push(self, name: str, payload: dict) -> None:
        for queue in list(self.clients):
            queue.put_nowait((name, payload))

    async def watch(self, index: int, host: str) -> None:
        """Relay one camera's alarm stream to the pages, as the app does."""
        url = f"http://{host}/cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat={_HEARTBEAT}"
        timeout = httpx.Timeout(connect=10, read=_HEARTBEAT * 4, write=10, pool=10)
        while True:
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    async with client.stream("GET", url, auth=self.auth()) as response:
                        response.raise_for_status()
                        logger.info("camera %d (%s): alarm stream attached", index, host)
                        self.push("status", {"cam": index, "online": True})
                        async for line in response.aiter_lines():
                            match = _EVENT_RE.search(line)
                            if match:
                                self.push("event", {"cam": index, "code": match[1], "action": match[2], "index": int(match[3])})
            except (httpx.HTTPError, OSError) as exc:
                logger.warning("camera %d: alarm stream dropped: %s", index, exc or type(exc).__name__)
            self.push("status", {"cam": index, "online": False})
            await asyncio.sleep(5)


async def index(request: web.Request) -> web.FileResponse:
    return web.FileResponse(WEB_DIR / "index.html")


async def remote(request: web.Request) -> web.Response:
    """The remote-control page: the same page, told that it is one (as the app's own server does)."""
    page = (WEB_DIR / "index.html").read_text(encoding="utf-8").replace("<body>", '<body class="remote">')
    return web.Response(text=page, content_type="text/html")


async def remote_config(request: web.Request) -> web.Response:
    """The settings without the camera password and the PIN, which the tablet never sends out either."""
    return web.json_response({**(request.app["dev"].saved or {}), "camera_password": "", "remote_pin": ""})


async def remote_save(request: web.Request) -> web.Response:
    dev, new = request.app["dev"], json.loads(await request.text())
    old = dev.saved or {"camera_password": dev.password}
    for secret in ("camera_password", "remote_pin"):   # left empty = unchanged
        new[secret] = new.get(secret) or old.get(secret, "")
    dev.saved = new
    return web.json_response({})


async def remote_state(request: web.Request) -> web.Response:
    dev = request.app["dev"]
    return web.json_response({"view": dev.view, "night": False, "ringing": time.time() < dev.rings_until,
                              "alarm": False, "offline": [], "lux": 120, "build": "dev server"})


async def remote_command(request: web.Request) -> web.Response:
    dev, told = request.app["dev"], json.loads(await request.text())
    logger.info("remote command: %s", told)
    dev.view = told.get("view", dev.view)
    if "ring" in told:
        dev.rings_until = time.time() + 6
    if told.get("stop"):
        dev.rings_until = 0.0
    return web.json_response({})


async def get_config(request: web.Request) -> web.Response:
    dev = request.app["dev"]
    # First run: only the login, so video and alarms work without typing it in.
    config = dev.saved or {"camera_user": dev.user, "camera_password": dev.password}
    return web.json_response(config)


async def save_config(request: web.Request) -> web.Response:
    request.app["dev"].saved = json.loads(await request.text())
    return web.json_response({"ok": True})


async def snapshot(request: web.Request) -> web.Response:
    dev = request.app["dev"]
    try:
        host = dev.hosts[int(request.match_info["cam"])]
    except (ValueError, IndexError):
        raise web.HTTPNotFound()
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.get(f"http://{host}/cgi-bin/snapshot.cgi?channel=1", auth=dev.auth())
    if response.status_code != 200:
        raise web.HTTPBadGateway()
    return web.Response(body=response.content, content_type="image/jpeg", headers={"Cache-Control": "no-store"})


async def slides(request: web.Request) -> web.Response:
    pictures = (".jpg", ".jpeg", ".png", ".webp")
    return web.json_response(sorted(p.name for p in SLIDES_DIR.iterdir() if p.suffix.lower() in pictures))


async def media(request: web.Request) -> web.Response:
    playable = (".mp3", ".m4a", ".aac", ".ogg", ".wav", ".flac", ".mp4", ".m4v", ".webm", ".jpg", ".jpeg", ".png", ".webp", ".gif")
    return web.json_response(sorted(p.name for p in MEDIA_DIR.iterdir() if p.suffix.lower() in playable))


async def events(request: web.Request) -> web.StreamResponse:
    dev = request.app["dev"]
    response = web.StreamResponse(headers={"Content-Type": "text/event-stream", "Cache-Control": "no-store"})
    await response.prepare(request)
    queue: asyncio.Queue = asyncio.Queue()
    dev.clients.add(queue)
    try:
        while True:
            try:
                item = await asyncio.wait_for(queue.get(), timeout=15)
            except asyncio.TimeoutError:
                await response.write(b": ping\n\n")
                continue
            if item is None:
                break
            await response.write(f"event: {item[0]}\ndata: {json.dumps(item[1])}\n\n".encode())
    except ConnectionResetError:
        pass
    finally:
        dev.clients.discard(queue)
    return response


async def test_alarm(request: web.Request) -> web.Response:
    cam = int(request.match_info["cam"])
    request.app["dev"].push("event", {"cam": cam, "code": "Test", "action": "Pulse", "index": 0})
    return web.json_response({"ok": True})


async def _background(app: web.Application):
    dev = app["dev"]
    tasks = [asyncio.create_task(dev.watch(i, host)) for i, host in enumerate(dev.hosts)]
    yield
    for task in tasks:
        task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)


async def _end_streams(app: web.Application) -> None:
    for queue in list(app["dev"].clients):
        queue.put_nowait(None)


async def _open_browser(app: web.Application) -> None:
    webbrowser.open(f"http://localhost:{PORT}")


async def _no_cache(request: web.Request, response: web.StreamResponse) -> None:
    response.headers.setdefault("Cache-Control", "no-cache")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    mimetypes.add_type("text/javascript", ".js")   # Windows' registry can get this wrong
    app = web.Application()
    app["dev"] = Dev()
    app.on_response_prepare.append(_no_cache)
    app.on_shutdown.append(_end_streams)
    app.cleanup_ctx.append(_background)
    if "--open" in sys.argv:
        app.on_startup.append(_open_browser)
    app.router.add_get("/", index)
    app.router.add_get("/dev/config", get_config)
    app.router.add_post("/dev/config", save_config)
    app.router.add_get("/remote", remote)
    app.router.add_get("/api/config", remote_config)
    app.router.add_post("/api/config", remote_save)
    app.router.add_get("/api/state", remote_state)
    app.router.add_get("/api/media", media)
    app.router.add_post("/api/command", remote_command)
    app.router.add_get("/dev/events", events)
    app.router.add_post("/dev/test-alarm/{cam}", test_alarm)
    app.router.add_get("/cam/{cam}/snapshot.jpg", snapshot)
    SLIDES_DIR.mkdir(exist_ok=True)
    app.router.add_get("/dev/slides", slides)
    app.router.add_static("/slides/", SLIDES_DIR)
    MEDIA_DIR.mkdir(exist_ok=True)
    app.router.add_get("/dev/media", media)
    app.router.add_static("/media/", MEDIA_DIR)
    app.router.add_static("/", WEB_DIR)
    logger.info("Page with stand-in native side on http://localhost:%d", PORT)
    try:
        web.run_app(app, host="127.0.0.1", port=PORT, access_log=None, print=None)
    except OSError:
        sys.exit(f"Port {PORT} is in use. Close the other server, or pick another port: serve.bat {PORT + 1}")


if __name__ == "__main__":
    main()
