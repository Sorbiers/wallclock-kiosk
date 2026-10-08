# Wall Clock — Tab M9 kiosk app

An independent Android app that turns a Lenovo Tab M9 into a wall clock,
weather station and security-camera monitor. No PC or server is involved: the
tablet talks to the cameras and to the weather service by itself.

```
Amcrest cameras ──alarms, snapshots, live video──> Tab M9 (Wall Clock app) <──internet── Open-Meteo
```

## What it shows

- **Clock** — big HH:MM (24 h), weekday and date, current weather.
- **Camera** — live video straight from the cameras; tap one for full screen, tap again to go back.
- **Weather** — current conditions, the next 24 hours, 7 days, plus time and date.
- **Alarm** — when a camera's tripwire or intrusion rule fires, the tablet shows
  pictures from that camera over whatever view is up, then returns to it.
  **Cancel** closes it at once.
- **Night mode** — dim violet text and a dimmed backlight between the set hours.
  An alarm always gets full brightness.
- **Slides** — a slideshow of your own pictures (shuffled, slow pan and zoom), optionally with the time in the corner.
- **Alarm clock** — up to four alarms (every day, Mon–Fri or Sat–Sun) with Snooze and Stop.
- **Remote control** — open the address shown at the bottom of the tablet's settings screen (`http://<tablet>:8080`) in a browser on a PC or phone on the same Wi-Fi: mode buttons, Stop alarm, and all settings. No login; the camera password is never sent out.
- **Settings** (the gear button) — city, night hours, backlight, camera login and
  addresses, alarm timing. **Test alarm** there shows a real alarm without
  walking past a camera.

Tap anywhere to bring up the Clock / Camera / Weather / gear buttons.

Each alarm picture is shown for `max(each-picture time, total time ÷ pictures)`:
with the defaults one picture stays 60 s and more than six get 10 s each.

## How it is built

| Path | What |
|---|---|
| `web/` | The page: everything you see. `app.js` is the kiosk, `rtsp.js` plays camera video (RTSP over the camera's WebSocket, decoded with WebCodecs). |
| `web/local.js` | This home's starting values (city, camera addresses). Not in git; copy `web/local.example.js` to create it. |
| `android/src/` | The native shell: a full-screen WebView plus what a page may not do — camera login for alarms and snapshots, settings storage, backlight, screen always on. |
| `android/build.py` | Builds `dist/wallclock.apk` with Google's SDK tools (no Gradle). |
| `android/wallclock.keystore` | Signing key. Not in git, so back it up: updates must be signed with the same key. |
| `dev/server.py` | Stand-in for the native shell, to try the page in a desktop browser. |

Build tools: a JDK, and the Android SDK in `F:\AndroidSdk` (build-tools, one
platform, platform-tools).

| Script | Does |
|---|---|
| `build.bat` | Builds `dist\wallclock.apk` |
| `deploy.bat` | Installs it on the tablet over USB and starts it (tablet settings are kept) |
| `sync-slides.bat [folder]` | Copies the slideshow pictures (default: `slides\`) to the tablet, shrunk to tablet size; removes ones you deleted |
| `serve.bat [port]` | Shows the page on this PC for debugging; camera login is borrowed from Butler's `.env` |

After editing files in `web\`: refresh the browser to see it on the PC, then
`build.bat` and `deploy.bat` to put it on the tablet. `serve.bat` opens the
browser itself; otherwise open <http://localhost:8080>. Add `?night=1` to preview night mode.

## Setting up the tablet

1. **Install** the app (above) and start **Wall Clock**.
2. **Settings (gear)**: enter the cameras' read-only login and check their
   addresses, then **Save**. The addresses should be reserved in the router so
   they never change.
3. **Video quality**: the app plays H.264 and uses each camera's *Main stream*
   by default. Measured on the Tab M9: both main streams at once (2960×1668 and
   2688×1520) play at about 20 frames a second with no backlog. *Sub stream 2*
   is lighter on Wi-Fi if you switch it on in the camera's own web page
   (Setup → Camera → Video); *Sub stream 1* is MJPEG on these cameras and is
   not supported.
4. **Kiosk**: the app keeps the screen on and hides the system bars by itself.
   When Android asks which Home app to use, choosing **Wall Clock → Always**
   makes it start by itself after a restart and makes the Home button return
   to it. To undo that: gear → **Android settings** → Apps → Default apps →
   Home app.
5. A battery-protection / charge-limit option, if the tablet has one, is kinder
   to a tablet that is always plugged in.

## Notes

- The camera login is stored on the tablet, in the app's private settings. Use
  a read-only camera user for it.
- The top-right corner shows a red note when a camera's alarm stream is not
  connected — alarms from that camera cannot arrive while it is there.
- The cameras are only read: alarm stream, snapshots, video. Nothing on them is
  changed.
