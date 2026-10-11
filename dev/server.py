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
standing in for the tablet: it keeps the settings, pretends to change view and
to ring when told to (a ring lasts a few seconds). The list of camera alarms,
which the app copies from the cameras' SD cards, is made up here, for the kiosk
page as well. Sign in as "dev" with the PIN 1234.
"""

import asyncio
import io
import json
import logging
import mimetypes
import os
import re
import sys
import time
import webbrowser
from pathlib import Path
from urllib.parse import quote

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
        self.view = "clock"   # the pretend tablet behind /remote: its page, and until when it pretends to ring
        self.rings_until = self.alarm_until = self.snoozed_until = 0.0

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


async def remote(request: web.Request) -> web.FileResponse:
    """The remote-control page, as the tablet serves it at "/"."""
    return web.FileResponse(WEB_DIR / "remote.html")


async def remote_settings(request: web.Request) -> web.Response:
    """The kiosk page told it is the remote control: every setting, as the tablet serves at "/settings"."""
    page = (WEB_DIR / "index.html").read_text(encoding="utf-8")
    return web.Response(text=page.replace("<body>", '<body class="remote" data-home="/remote">'), content_type="text/html")


def _signed_in(request: web.Request) -> None:
    """The pretend tablet knows one user. (The real rules are in Remote.java; see tools/test_remote.py.)"""
    if not request.headers.get("X-Pin"):
        raise web.HTTPUnauthorized()
    if (request.headers.get("X-User", "").lower(), request.headers["X-Pin"]) != ("dev", "1234"):
        raise web.HTTPUnauthorized()


async def remote_config(request: web.Request) -> web.Response:
    """The settings without the camera password and the PINs, which the tablet never sends out either."""
    _signed_in(request)
    config = json.loads(json.dumps(request.app["dev"].saved or {}))
    for user in config.get("remote", {}).get("users", []):
        user["pin"] = ""
    return web.json_response({**config, "camera_password": "", "remote_pin": ""})


async def remote_save(request: web.Request) -> web.Response:
    _signed_in(request)
    dev, new = request.app["dev"], json.loads(await request.text())
    old = dev.saved or {"camera_password": dev.password}
    new["camera_password"] = new.get("camera_password") or old.get("camera_password", "")   # left empty = unchanged
    new.pop("remote", None)   # who may use the remote control is the tablet's own business: it stays as it is
    if "remote" in old:
        new["remote"] = old["remote"]
    dev.saved = new
    return web.json_response({})


async def remote_state(request: web.Request) -> web.Response:
    _signed_in(request)
    dev, now = request.app["dev"], time.time()
    return web.json_response({"user": request.headers.get("X-User", ""), "tablet": {
        "view": dev.view, "night": False, "ringing": now < dev.rings_until, "snoozed": now < dev.snoozed_until,
        "alarm": now < dev.alarm_until, "alarmText": "Driveway \u00b7 Test alarm \u00b7 12:00:00", "offline": ["Rear"],
        "weather": "\u26c5 12\u00b0 Partly cloudy \u2191 14\u00b0  \u2193 6\u00b0", "lux": 120, "locale": "en-CA", "build": "dev server"}})


async def remote_command(request: web.Request) -> web.Response:
    _signed_in(request)
    dev, told = request.app["dev"], json.loads(await request.text())
    logger.info("remote command: %s", told)
    dev.view = told.get("view", dev.view)
    if "ring" in told:
        dev.rings_until = time.time() + 6
    if told.get("test"):
        dev.alarm_until = time.time() + 8
    if told.get("snooze") and time.time() < dev.rings_until:
        dev.rings_until, dev.snoozed_until = 0.0, time.time() + 20
    if told.get("stop"):
        dev.rings_until = dev.alarm_until = dev.snoozed_until = 0.0
    return web.json_response({})


def _made_up_alarms() -> dict:
    """What the app keeps of the cameras' own alarm pictures, made up: three alarms with a few pictures each."""
    now = int(time.time() * 1000)
    made = [(0, "Driveway", ["CrossLineDetection"], 40, 5), (1, "Rear", ["CrossRegionDetection"], 190, 3),
            (0, "Driveway", ["CrossLineDetection", "CrossRegionDetection"], 1500, 9)]
    return {"failed": [], "alarms": [
        {"cam": cam, "name": name, "at": now - minutes * 60000, "codes": codes,
         "shots": [{"at": now - minutes * 60000 + i * 1000,
                    "file": f"{now - minutes * 60000 + i * 1000}_{cam}_{'+'.join(codes)}_{number:06X}{i:06X}.jpg"} for i in range(count)]}
        for number, (cam, name, codes, minutes, count) in enumerate(made, 1)]}


def _made_up_shot(request: web.Request) -> web.Response:
    """A made-up picture for that list: a flat colour with its name written on it."""
    from PIL import Image, ImageDraw
    file = request.query.get("file", "")
    if not re.fullmatch(r"\d+_\d+_[\w+]*_[0-9A-F]+\.jpg", file):
        raise web.HTTPNotFound()
    number = sum(map(ord, file))
    size = (1280, 720) if request.query.get("size") == "full" else (480, 270)
    picture = Image.new("RGB", size, ((number * 53) % 120 + 40, (number * 31) % 120 + 40, (number * 17) % 120 + 60))
    ImageDraw.Draw(picture).text((size[0] // 12, size[1] // 8), file, fill="white")
    jpeg = io.BytesIO()
    picture.save(jpeg, "JPEG", quality=80)
    return web.Response(body=jpeg.getvalue(), content_type="image/jpeg")


async def card_alarms(request: web.Request) -> web.Response:
    """For the kiosk page: the alarms the cameras recorded (made up here)."""
    return web.json_response(_made_up_alarms())


async def card_shot(request: web.Request) -> web.Response:
    return _made_up_shot(request)


async def card_since(request: web.Request) -> web.Response:
    """During an alarm: the pictures copied since that moment. Made up: one more every two seconds, five in all."""
    began = int(request.query.get("ms", "0")) + 20000   # the page asks from a little before the alarm
    count = max(0, min(5, int((time.time() * 1000 - began) // 2000)))
    cam = request.match_info["cam"]
    return web.json_response([{"at": began + i * 1000, "file": f"{began + i * 1000}_{cam}_CrossLineDetection_{i:012X}.jpg"}
                              for i in range(count)])


async def remote_events(request: web.Request) -> web.Response:
    _signed_in(request)
    return web.json_response(_made_up_alarms())


async def remote_shot(request: web.Request) -> web.Response:
    _signed_in(request)
    return _made_up_shot(request)


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


async def _relay(url: str, content_type: str) -> web.Response:
    browser = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"}
    async with httpx.AsyncClient(timeout=20, follow_redirects=True, headers=browser) as client:
        response = await client.get(url)
    if response.status_code != 200:
        raise web.HTTPBadGateway()
    return web.Response(body=response.content, content_type=content_type, headers={"Cache-Control": "no-store"})


async def feed_yahoo(request: web.Request) -> web.Response:
    """Market data for the newspaper page, which a web page is not let to read itself."""
    symbol = request.query.get("symbol", "")
    if not re.fullmatch(r"[A-Za-z0-9^=.\-]{1,12}", symbol):
        raise web.HTTPBadRequest()
    return await _relay(f"https://query1.finance.yahoo.com/v8/finance/chart/{quote(symbol)}?range=3mo&interval=1d", "application/json")


async def feed_news(request: web.Request) -> web.Response:
    """Headlines for the newspaper page: the country's edition, or those about one town."""
    lang, country, city = request.query.get("lang", ""), request.query.get("country", ""), request.query.get("city", "")
    if not re.fullmatch(r"[a-z]{2}", lang) or not re.fullmatch(r"[A-Z]{2}", country):
        raise web.HTTPBadRequest()
    section = f"/headlines/section/geo/{quote(city)}" if city else ""
    return await _relay(f"https://news.google.com/rss{section}?hl={lang}-{country}&gl={country}&ceid={country}:{lang}", "text/xml")


async def feed_church(request: web.Request) -> web.Response:
    """The Russian Orthodox calendar for a day."""
    date = request.query.get("date", "")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
        raise web.HTTPBadRequest()
    return await _relay(f"https://azbyka.ru/days/api/day/{date}.json", "application/json")


async def feed_jokes(request: web.Request) -> web.Response:
    return await _relay("https://www.anekdot.ru/rss/export_j.xml", "text/xml")


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
    app.router.add_get("/settings", remote_settings)
    app.router.add_get("/api/events", remote_events)
    app.router.add_get("/api/shot", remote_shot)
    app.router.add_get("/api/config", remote_config)
    app.router.add_post("/api/config", remote_save)
    app.router.add_get("/api/state", remote_state)
    app.router.add_get("/api/media", media)
    app.router.add_post("/api/command", remote_command)
    app.router.add_get("/dev/events", events)
    app.router.add_post("/dev/test-alarm/{cam}", test_alarm)
    app.router.add_get("/cam/{cam}/snapshot.jpg", snapshot)
    app.router.add_get("/cam/alarms", card_alarms)
    app.router.add_get("/cam/shot", card_shot)
    app.router.add_get("/cam/{cam}/since", card_since)
    SLIDES_DIR.mkdir(exist_ok=True)
    app.router.add_get("/dev/slides", slides)
    app.router.add_static("/slides/", SLIDES_DIR)
    MEDIA_DIR.mkdir(exist_ok=True)
    app.router.add_get("/dev/media", media)
    app.router.add_get("/feed/yahoo", feed_yahoo)
    app.router.add_get("/feed/news", feed_news)
    app.router.add_get("/feed/church", feed_church)
    app.router.add_get("/feed/jokes", feed_jokes)
    app.router.add_static("/media/", MEDIA_DIR)
    app.router.add_static("/", WEB_DIR)
    logger.info("Page with stand-in native side on http://localhost:%d", PORT)
    try:
        web.run_app(app, host="127.0.0.1", port=PORT, access_log=None, print=None)
    except OSError:
        sys.exit(f"Port {PORT} is in use. Close the other server, or pick another port: serve.bat {PORT + 1}")


if __name__ == "__main__":
    main()
