# Wall Clock kiosk

A small Android app that turns a spare tablet into a wall clock, weather
station and monitor for **Amcrest / Dahua** security cameras. There is no
server and no account: the tablet talks to the cameras and to the weather
services by itself. The whole app is under 100 KB and has no dependencies.

```
Amcrest/Dahua cameras ──alarms, snapshots, live video──> tablet (Wall Clock) <──internet── weather
```

## What it does

- **Clock** — big HH:MM (24 h), weekday and date, current weather, and a notice
  when the weather is about to change. Upright, the hours stack over the minutes.
- **Camera** — live video straight from the cameras; a tap steps through all
  cameras and each one alone.
- **Weather** — now, the next 24 hours and 7 days (Open-Meteo; in Canada the
  current conditions come from an Environment Canada station).
- **Slides** — a slideshow of your own pictures, optionally on a daily schedule
  and with the clock page in between.
- **Camera alarms** — when a camera's tripwire or intrusion rule fires, pictures
  from that camera appear over whatever is showing, with an optional short beep.
  Upright, the camera also plays live underneath.
- **Alarm clock** — four alarms with a choice of sounds, Snooze and Stop.
- **Night mode** — its own colour and backlight, by the clock or when the room
  is dark (light sensor).
- **Remote control** — a page served by the tablet itself (`http://<tablet>:8080`)
  to switch modes and edit every setting from a PC or phone.
- **Kiosk** — full screen, screen always on, landscape or portrait; can be the
  tablet's Home app so it starts by itself.

## What it needs, and its limits

- **Cameras: Amcrest / Dahua only.** Alarms, snapshots and video all use that
  family's own interfaces (event stream, `snapshot.cgi`, RTSP over the camera's
  WebSocket, H.264). Other brands will not work. The settings screen has room
  for two cameras.
- **Tablet: Android 11 or newer** with a current system WebView. Developed and
  tested on one device, a Lenovo Tab M9 (Android 13), with two Amcrest 5 MP
  cameras; there it plays both full-resolution streams at about 20 frames a second.
- **The remote control has no login.** Anyone on the same Wi-Fi can change the
  settings. The camera password is never sent to it.
- The camera login is stored on the tablet in the app's private settings: use a
  view-only camera user.
- The cameras are only read; nothing on them is changed.
- A hobby project: lightly tested, no automated tests, English interface.

## Building and installing

You need Python 3 (with Pillow for the slideshow sync), a JDK, and the Android
SDK (build-tools, one platform, platform-tools). There is no Gradle: the build
script calls the SDK tools directly.

| Script | Does |
|---|---|
| `build.bat` | Builds `dist\wallclock.apk` |
| `deploy.bat` | Installs it over USB (USB debugging on) and starts it; settings on the tablet are kept |
| `sync-slides.bat [folder]` | Copies the slideshow pictures (default: `slides\`) to the tablet, shrunk to tablet size |
| `test-alarm.bat [camera]` | Pretends a camera alarm on the tablet; pictures and live view are real |
| `serve.bat [port]` | Shows the page in a desktop browser for development |

Machine-specific paths go in `local.bat` (see `local.example.bat`). The first
build creates a signing key, `android/wallclock.keystore`; keep it, because an
update must be signed with the same key. The scripts are Windows batch files
around small Python scripts, which also run on their own elsewhere.

## Setting up the tablet

1. Install the app and start **Wall Clock**.
2. Tap the screen, then the gear: enter the camera login and addresses, your
   city, and anything else you want to change. **Save**.
3. When Android asks which Home app to use, choosing Wall Clock makes it start
   by itself after a restart. To undo that: gear → Android settings → Apps →
   Default apps → Home app.

Starting values for your own home (city, camera addresses) can be baked into
your build with `web/local.js`; see `web/local.example.js`.

## How it is built

| Path | What |
|---|---|
| `web/` | The page: everything you see. `app.js` is the kiosk; `rtsp.js` plays camera video (RTSP over the camera's WebSocket, decoded with WebCodecs). |
| `android/src/` | The native shell: a full-screen WebView plus what a page may not do — camera login for alarms and snapshots, settings storage, backlight, light sensor, the remote-control server. |
| `android/build.py` | Builds the APK with aapt2, javac, d8, zipalign and apksigner. |
| `dev/server.py` | Stands in for the native shell so the page runs in a desktop browser. |
| `tools/` | The slideshow sync and the test alarm. |

## Licence

MIT. See [LICENSE](LICENSE).
