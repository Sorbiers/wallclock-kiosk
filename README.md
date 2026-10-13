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
- **Events** — the camera alarms of the last 24 hours with the pictures the
  cameras took themselves. The app copies them from the cameras' SD cards and
  keeps them for a day, so the list shows at once, is whole whether or not the
  tablet was awake, and does not need the cameras; a tap opens a picture full
  screen. With an alarm's own pictures come the camera's other ones from a few
  seconds around it. Where the camera recorded a video of the alarm, a button
  fetches it from the camera's card and plays it.
- **Weather** — now, the next 24 hours and 7 days (Open-Meteo; in Canada the
  current conditions come from an Environment Canada station).
- **News** — a newspaper page: small charts of USD/CAD, bitcoin, the Bank of
  Canada rate, oil and the main stock indexes (a tap enlarges one), pump prices
  in a Quebec town, air quality, the next public holiday, the picture of the
  day, this day in history and the headlines; optionally Russian picks: name
  days, the Orthodox calendar, history and a joke of the day. The settings
  choose what is shown. All from free services without a key: the Bank of
  Canada, CoinGecko, Yahoo Finance (unofficial, so it may stop working),
  Quebec's Régie de l'énergie, Open-Meteo, Nager.Date, Wikipedia and Wikimedia
  Commons, Google News, azbyka.ru and anekdot.ru.
- **Slides** — a slideshow of your own pictures, optionally on a daily schedule
  and with the clock page in between. Day and night can each have a folder of
  pictures of their own.
- **Camera alarms** — when a camera's tripwire or intrusion rule fires, pictures
  from that camera appear over whatever is showing, with a sound of your choice
  (or none), for as long and as loud as you set. Upright, the camera also plays
  live underneath.
- **Alarm clock** — four alarms, daily, on weekdays, at weekends or just once;
  each rings a sound of its own choice or plays a YouTube link, music or a video
  from the tablet, or shows a picture. Snooze and Stop.
- **Night mode** — its own colour and backlight, by the clock or when the room
  is dark (light sensor). By day and by night the backlight is a fixed level or
  follows the room light; a tap brightens the screen for a few seconds, an
  alarm can too, and a double tap always gives full light for half a minute.
- **Remote control** — a page served by the tablet itself (`http://<tablet>:8080`;
  the settings can name another port), made like a TV remote: what the wall clock is doing, keys to switch its page
  and to stop an alarm, the list of camera alarms with their pictures, and a
  Settings key to every setting (which can also save the settings to a file
  and restore them; the camera password and the PINs never leave the tablet).
- **Kiosk** — full screen, screen always on, landscape or portrait; can be the
  tablet's Home app so it starts by itself. It restarts itself every night and
  whenever its page dies; the settings show when the app was built.

## What it needs, and its limits

- **Cameras: Amcrest / Dahua only.** Alarms, snapshots and video all use that
  family's own interfaces (event stream, `snapshot.cgi`, RTSP over the camera's
  WebSocket, H.264). Other brands will not work. The settings screen has room
  for two cameras.
- **Tablet: Android 11 or newer** with a current system WebView. Developed and
  tested on one device, a Lenovo Tab M9 (Android 13), with two Amcrest 5 MP
  cameras; there it plays both full-resolution streams at about 20 frames a second.
- **The remote control needs a user**, with a name and a PIN, listed in the
  tablet's settings; with nobody listed it is off. It answers only the tablet's
  own /24 network and the networks you add there (a VPN's, say). It is plain
  HTTP, so do not forward its port from the Internet, and treat the PIN as a
  lock against other people in the house, not against a determined attacker.
  The camera password and the PINs are never sent to it.
- The camera login is stored on the tablet in the app's private settings: use a
  camera user that can only view. For the Events page that user must also be
  allowed to search the camera's recordings, and the camera must have an SD
  card and be set to save a picture when a rule fires; for the videos, to
  record them as MP4 files in H.264.
- The cameras are only read; nothing on them is changed.
- A hobby project: lightly tested, English interface. The one automated test,
  `python tools/test_remote.py`, checks who the remote control lets in.

## Building and installing

You need Python 3 (with Pillow for the slideshow sync), a JDK, and the Android
SDK (build-tools, one platform, platform-tools). There is no Gradle: the build
script calls the SDK tools directly.

| Script | Does |
|---|---|
| `build.bat [--debug]` | Builds `dist\wallclock.apk`; `--debug` makes the page inspectable over USB (chrome://inspect) |
| `deploy.bat` | Installs it on the tablet and starts it; settings on the tablet are kept |
| `sync-slides.bat [folder]` | Copies the slideshow pictures (default: `slides\`, with the folders inside it) to the tablet, shrunk to tablet size |
| `sync-media.bat [folder]` | Copies what the alarm clock can play (default: `media\`): music, videos, pictures |
| `wifi-on.bat` | Lets the scripts above reach the tablet over Wi-Fi, without the cable |
| `test-alarm.bat [camera]` | Pretends a camera alarm on the tablet; pictures and live view are real |
| `serve.bat [port]` | Shows the page in a desktop browser for development; `/remote` there is the remote-control page with a pretend tablet (sign in as `dev`, PIN `1234`) |

`deploy.bat` and the two sync scripts go by USB cable when the tablet is plugged in
(USB debugging on). To do without the cable, run `wifi-on.bat` once with the
cable in; the tablet forgets that when it restarts. While it is on, the tablet's
debug port is open on your network to the computers it has already allowed.

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
