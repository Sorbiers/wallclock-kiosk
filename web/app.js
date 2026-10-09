/* Wall kiosk page: clock, weather, camera monitor, alarm slideshow, settings.

   Runs inside the Android app's WebView. Whatever a web page may not do itself
   is done by the native side (window.Android): reading the camera's alarm
   stream and snapshots (both need a login the browser cannot give), storing
   the settings and setting the backlight. In a desktop browser dev/server.py
   stands in for the native side, so the page can be tried without a tablet. */
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const pad = (n) => String(n).padStart(2, "0");
  const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
  const params = new URLSearchParams(location.search);
  // Opened in another device's browser (served by the tablet, see Remote.java):
  // only the mode buttons and the settings, acting on the tablet.
  const REMOTE = document.body.classList.contains("remote");
  const VIEWS = ["clock", "camera", "events", "weather", "slides", "settings"];
  const DAY_PEEK_MS = 5000;   // a tap at night shows day mode for this long
  const TEST_CODE = "Test";
  const EVENT_LABELS = {
    CrossLineDetection: "Line crossed",
    CrossRegionDetection: "Zone intrusion",
    Test: "Test alarm",
  };
  const labelOf = (codes) => codes.map((code) => EVENT_LABELS[code] || code).join(" + ");

  const DEFAULTS = {
    locale: "en-CA",
    weather: { city: "", country_code: "", units: "metric", refresh_minutes: 15 },
    night: {
      by: "time", start: "22:00", end: "07:00",   // by: "time" = these hours, "dark" = when the room is dark
      dark_lux: 5, color: "#8f5cff", dim: 0.45, brightness: 0.03,
    },
    day: {
      brightness: -1, color: "#ffffff",   // brightness -1 = leave the backlight to Android
      auto: { on: true, min: 0.1, max: 1, full_lux: 300 },   // or scale it by the room light ourselves
    },
    views: { camera_timeout_minutes: 10, weather_timeout_minutes: 0, buttons_hide_seconds: 6 },
    alarm: {
      min_total_seconds: 60, min_picture_seconds: 10, min_pictures: 3, max_pictures: 12,
      capture_interval_seconds: 5, sound: true, codes: ["CrossLineDetection", "CrossRegionDetection"],
    },
    slides: {
      seconds: 8, transition: "random", motion: true, clock: true,
      brightness_day: -1, brightness_night: -1,   // -1 = the usual day / night backlight
      clock_every: 0, clock_seconds: 20,          // the clock page after every N pictures; 0 = never
      auto_start: "", auto_stop: "",              // "HH:MM": run by itself between these; empty = off
    },
    wake: { sound: "chime", seconds: 30, rounds: 3, media_minutes: 10 },   // how the alarm clock rings
    clock_alarms: [0, 1, 2, 3].map(() => ({ on: false, time: "07:00", days: "all", sound: "", media: "", youtube: "" })),
    camera_user: "",
    camera_password: "",
    remote_pin: "",   // the remote control works only while a PIN is set
    cameras: [
      { name: "Camera 1", host: "", stream: 0 },
      { name: "Camera 2", host: "", stream: 0 },
    ],
  };
  // This home's own starting values (city, camera addresses) come from local.js,
  // which is kept out of git; see local.example.js.
  const BASE = merge(DEFAULTS, window.KIOSK_LOCAL);
  let cfg = BASE;

  function merge(base, extra) {
    if (Array.isArray(base) || typeof base !== "object" || base === null) return extra === undefined ? base : extra;
    const out = {};
    Object.keys(base).forEach((key) => { out[key] = merge(base[key], extra ? extra[key] : undefined); });
    return out;
  }
  function readStore(key) {
    try { return JSON.parse(localStorage.getItem(key) || "null"); } catch (e) { return null; }
  }
  function writeStore(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) {}
  }
  function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  }

  // --- the native side -------------------------------------------------------
  const native = window.Android ? {
    getConfig: async () => window.Android.getConfig(),
    saveConfig: async (json) => window.Android.saveConfig(json),
    configure: (json) => window.Android.configure(json),
    setBrightness: (level) => window.Android.setBrightness(level),
    openSystemSettings: () => window.Android.openSystemSettings(),
    listSlides: async () => JSON.parse(window.Android.listSlides()),
    listMedia: async () => JSON.parse(window.Android.listMedia()),
    remoteAddress: () => window.Android.remoteAddress(),
    lux: () => window.Android.lux(),   // room light from the tablet's sensor; below 0 = no sensor
    clearCache: () => window.Android.clearCache(),
    restart: () => window.Android.restart(),
  } : REMOTE ? {
    getConfig: () => api("/api/config"),
    saveConfig: (json) => api("/api/config", json),
    configure: () => {},
    setBrightness: () => {},
    listSlides: async () => [],
    listMedia: () => api("/api/media").then(JSON.parse),
  } : {   // desktop browser: the dev server plays the native part
    getConfig: () => fetch("/dev/config").then((r) => r.text()),
    saveConfig: (json) => fetch("/dev/config", { method: "POST", body: json }),
    configure: () => {
      const source = new EventSource("/dev/events");
      source.addEventListener("event", (e) => onCameraEvent(JSON.parse(e.data)));
      source.addEventListener("status", (e) => onCameraStatus(JSON.parse(e.data)));
    },
    setBrightness: () => {},
    listSlides: () => fetch("/dev/slides").then((r) => r.json()),
    listMedia: () => fetch("/dev/media").then((r) => r.json()),
  };
  window.kioskNative = { event: onCameraEvent, status: onCameraStatus, back: () => show("clock"), remote: onRemote };

  // The remote page sends commands; the tablet carries them out.
  function command(what) {
    return api("/api/command", JSON.stringify(what));
  }

  // Remote side: every request carries the PIN, kept in this browser. The
  // tablet answers 401 when it is missing or wrong, and we ask for it.
  async function api(path, body) {
    for (;;) {
      const options = { headers: { "X-Pin": localStorage.getItem("kiosk-pin") || "" } };
      if (body != null) { options.method = "POST"; options.body = body; }
      const reply = await fetch(path, options);
      if (reply.ok) return reply.text();
      if (reply.status === 403) {
        alert("The remote control is off. Set a PIN in the settings on the tablet itself (gear, General).");
        throw new Error("remote control is off");
      }
      if (reply.status === 429) {
        alert("Too many wrong PINs. Try again in a minute.");
        throw new Error("locked");
      }
      const pin = prompt("PIN for the wall clock");
      if (pin === null) throw new Error("no PIN given");
      localStorage.setItem("kiosk-pin", pin.trim());
    }
  }
  // Colour pickers on the remote page: each change is shown on the tablet at
  // once, in the mode the colour belongs to (so a night colour can be judged by
  // day). It lasts until Save, or a few seconds after the last change.
  const PREVIEW_MS = 10000;
  const COLOURS = { "night.color": "--night", "day.color": "--day" };
  let previewMode = null;
  let previewEnd = null;
  let previewSend = null;
  let previewSentAt = 0;

  function sendPreview(path, value) {   // remote side; at most five a second while dragging
    clearTimeout(previewSend);
    const send = () => { previewSentAt = Date.now(); command({ preview: { path: path, value: value } }); };
    if (Date.now() - previewSentAt > 200) send(); else previewSend = setTimeout(send, 200);
  }

  function preview(change) {            // tablet side
    if (!COLOURS[change.path] || !/^#[0-9a-f]{6}$/i.test(change.value)) return;
    const root = document.documentElement.style;
    root.setProperty(COLOURS[change.path], change.value);
    previewMode = change.path === "night.color" ? "night" : "day";
    clearTimeout(previewEnd);
    previewEnd = setTimeout(() => {
      previewMode = null;
      root.setProperty("--night", cfg.night.color);
      root.setProperty("--day", cfg.day.color);
      updateMode();
    }, PREVIEW_MS);
    updateMode();
  }

  function onRemote(what) {
    if (what.view && VIEWS.includes(what.view)) show(what.view);
    if (what.test) testAlarm(what.cam);
    if (what.ring) ring(what.ring);
    if (what.preview) preview(what.preview);
    if (what.restart && native.restart) native.restart();
    if (what.stop) {
      if (ringing) stopRinging(0);
      if (alarm.open) closeAlarm(true);
    }
  }

  // --- seven-segment digits (SVG, so they look the same on every device) ---
  const SVG_NS = "http://www.w3.org/2000/svg";
  const SEGMENTS = (function () {
    const W = 100, H = 180, T = 22, G = 3, h = T / 2, mid = H / 2;   // T = segment thickness
    const horiz = (y) => [[h + G, y], [T + G, y - h], [W - T - G, y - h], [W - h - G, y], [W - T - G, y + h], [T + G, y + h]];
    const vert = (x, y1, y2) => [[x, y1], [x + h, y1 + h], [x + h, y2 - h], [x, y2], [x - h, y2 - h], [x - h, y1 + h]];
    return {
      a: horiz(h), g: horiz(mid), d: horiz(H - h),
      f: vert(h, h + G, mid - G), b: vert(W - h, h + G, mid - G),
      e: vert(h, mid + G, H - h - G), c: vert(W - h, mid + G, H - h - G),
    };
  })();
  const DIGIT_SEGMENTS = ["abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg"];
  const DIGIT_X = [0, 128, 300, 428];
  const COLON = [[250, 46], [278, 46], [278, 74], [250, 74]];   // dot as thick as a segment

  function polygon(points, cls) {
    const node = document.createElementNS(SVG_NS, "polygon");
    node.setAttribute("points", points.map((p) => p.join(",")).join(" "));
    node.setAttribute("class", cls);
    return node;
  }

  // Four digits in a row with a colon, or (stacked) the hours above the minutes
  // so an upright tablet can show them about twice as large.
  const ROW = "-18 -2 548 184", STACK = "-18 -2 248 390", STACK_ROW_Y = 206, SLANT = " skewX(-5)";

  function buildDigits(host) {
    const svg = document.createElementNS(SVG_NS, "svg");
    const digits = DIGIT_X.map(() => {
      const group = svg.appendChild(document.createElementNS(SVG_NS, "g"));
      const segs = {};
      Object.keys(SEGMENTS).forEach((name) => { segs[name] = group.appendChild(polygon(SEGMENTS[name], "seg")); });
      return { group: group, segs: segs };
    });
    const dots = [0, 60].map((dy) => {
      const dot = svg.appendChild(polygon(COLON.map((p) => [p[0], p[1] + dy]), "seg on"));
      dot.setAttribute("transform", SLANT.trim());
      return dot;
    });
    function arrange(stacked) {
      svg.setAttribute("viewBox", stacked ? STACK : ROW);
      digits.forEach((digit, i) => {
        const x = stacked ? DIGIT_X[i % 2] : DIGIT_X[i], y = stacked && i > 1 ? STACK_ROW_Y : 0;
        digit.group.setAttribute("transform", "translate(" + x + "," + y + ")" + SLANT);   // each row slants on its own
      });
      dots.forEach((dot) => { dot.style.display = stacked ? "none" : ""; });
      host.classList.toggle("stacked", stacked);
    }
    arrange(false);
    host.appendChild(svg);
    return {
      arrange: arrange,
      set(hhmm) {
        digits.forEach((digit, i) => {
          const lit = DIGIT_SEGMENTS[+hhmm[i]];
          Object.keys(digit.segs).forEach((name) => digit.segs[name].classList.toggle("on", lit.includes(name)));
        });
      },
      // Driven by the clock tick, so a pulsing colon proves the page is alive.
      beat(on) { dots.forEach((dot) => dot.classList.toggle("rest", !on)); },
    };
  }

  // --- sounds (generated, so there are no audio files to ship) ---------------
  const CAMERA_BEEP = [[660, 0, 0.25], [880, 0.22, 0.28]];               // half a second, two soft notes
  // Alarm clock sounds: notes as [frequency, starts at, lasts] in seconds, played again every `every` s.
  const SOUNDS = {
    chime: { name: "Chime", every: 2.5, notes: [[523, 0, 0.4], [659, 0.3, 0.4], [784, 0.6, 0.6]] },
    rising: { name: "Rising", every: 2, notes: [[392, 0, 0.25], [494, 0.2, 0.25], [587, 0.4, 0.25], [784, 0.6, 0.5]] },
    bell: { name: "Bell", every: 2.2, notes: [[880, 0, 1.6], [1760, 0, 0.8]] },
    beep: { name: "Beep-beep", every: 1.6, notes: [[1000, 0, 0.12], [1000, 0.2, 0.12], [1000, 0.4, 0.12], [1000, 0.6, 0.12]] },
    soft: { name: "Soft pulse", every: 2, notes: [[440, 0, 0.9]] },
  };
  let audio = null;

  function play(notes) {
    try {
      audio = audio || new (window.AudioContext || window.webkitAudioContext)();
      if (audio.state === "suspended") audio.resume();
      notes.forEach((note) => {
        const start = audio.currentTime + note[1];
        const osc = audio.createOscillator(), amp = audio.createGain();
        osc.frequency.value = note[0];
        amp.gain.setValueAtTime(0, start);
        amp.gain.linearRampToValueAtTime(0.3, start + 0.02);
        amp.gain.exponentialRampToValueAtTime(0.001, start + note[2]);
        osc.connect(amp).connect(audio.destination);
        osc.start(start);
        osc.stop(start + note[2] + 0.05);
      });
    } catch (e) { /* no sound is not worth breaking an alarm for */ }
  }

  // --- clock, date, day / night ---------------------------------------------
  const clocks = ["clock", "cam", "wx", "ss", "ev"].map((name) => buildDigits($(name + "-digits")));
  // Upright tablet: the main clock stacks; the small header clocks stay in a row.
  const upright = window.matchMedia("(orientation: portrait)");
  const arrangeClock = () => clocks[0].arrange(upright.matches);
  upright.addEventListener("change", arrangeClock);
  arrangeClock();
  let shownTime = "";
  let night = false;
  let lastBrightness = null;

  function isNight(now) {
    if (params.has("night")) return params.get("night") === "1";   // ?night=1 previews it
    const dark = cfg.night.by === "dark" ? roomIsDark() : null;
    return dark === null ? inSpan(cfg.night.start, cfg.night.end, now) : dark;
  }

  // Night mode by room light. The reading must stay across the limit for a few
  // seconds (a passing shadow does nothing), and it takes twice the limit to
  // leave night mode, so the screen's own glow cannot make it flip back and forth.
  const DARK_SETTLE_MS = 5000;
  let dark = false;
  let darkFlipAt = 0;

  function roomIsDark() {
    const lux = native.lux ? native.lux() : -1;
    if (lux < 0) return null;   // no sensor here: the caller falls back to the hours
    const wanted = lux < cfg.night.dark_lux * (dark ? 2 : 1);
    if (wanted === dark) darkFlipAt = 0;
    else if (!darkFlipAt) darkFlipAt = Date.now();
    else if (Date.now() - darkFlipAt > DARK_SETTLE_MS) { dark = wanted; darkFlipAt = 0; }
    return dark;
  }

  // Is `now` between two "HH:MM" times? The span may run past midnight.
  function inSpan(from, until, now) {
    const mins = (hhmm) => { const p = hhmm.split(":"); return +p[0] * 60 + +p[1]; };
    const t = now.getHours() * 60 + now.getMinutes();
    const start = mins(from), end = mins(until);
    return start <= end ? t >= start && t < end : t >= start || t < end;
  }

  // Night mode (dim violet, low backlight) gives way to day mode whenever the
  // screen is being used: settings, the events list, an alarm, or the seconds
  // after a tap.
  // The backlight, by priority: the slideshow's own setting, then full light
  // while in use, then the night level or the room light.
  function updateMode() {
    const inUse = view === "settings" || view === "events" || alarm.open || ringing || Date.now() - lastTouch < DAY_PEEK_MS;
    night = previewMode ? previewMode === "night" : isNight(new Date()) && !inUse;
    document.body.classList.toggle("night", night);
    const slideLight = night ? cfg.slides.brightness_night : cfg.slides.brightness_day;   // the slideshow's own backlight
    const level = view === "slides" && !ss.interlude && !ss.fading && slideLight >= 0 ? slideLight
      : night ? cfg.night.brightness : dayLight(inUse);
    if (level === lastBrightness) return;
    lastBrightness = level;
    native.setBrightness(level);
  }

  // Day backlight by the room light: from day.auto.min in the dark up to
  // day.auto.max at full_lux, on a logarithmic scale (as the eye judges light).
  // Android's own adaptive brightness is flat over the lower range on some
  // tablets, which is why the app can do this itself.
  let calmLux = -1;
  function dayLight(inUse) {
    const auto = cfg.day.auto;
    const lux = auto.on && native.lux ? native.lux() : -1;
    if (lux < 0) return cfg.day.brightness;   // switched off, or no sensor
    const low = Math.max(0, auto.min), high = Math.max(low, auto.max);
    if (inUse) return high;   // someone is at the screen: full light, however dark the room
    calmLux = calmLux < 0 ? lux : calmLux + (lux - calmLux) * 0.25;   // smooth out the sensor's jitter
    const share = Math.min(1, Math.log(1 + calmLux) / Math.log(1 + Math.max(1, auto.full_lux)));
    return Math.round((low + (high - low) * share) * 100) / 100;   // whole percents: no constant fiddling
  }

  function renderClock(force) {
    const now = new Date();
    const hhmm = pad(now.getHours()) + pad(now.getMinutes());
    clocks.forEach((c) => c.beat(now.getSeconds() % 2 === 0));
    if (hhmm === shownTime && !force) return;
    shownTime = hhmm;
    clocks.forEach((c) => c.set(hhmm));
    const weekday = new Intl.DateTimeFormat(cfg.locale, { weekday: "long" }).format(now);
    const date = new Intl.DateTimeFormat(cfg.locale, { day: "numeric", month: "long" }).format(now);
    ["clock", "cam", "wx", "ev"].forEach((name) => { $(name + "-date").textContent = weekday + " \u00B7 " + date; });
  }

  // --- views and their buttons --------------------------------------------
  let view = "clock";
  let lastTouch = Date.now();
  let navTimer = null;

  function show(name) {
    view = name;
    VIEWS.forEach((v) => { $("view-" + v).hidden = v !== name; });
    document.querySelectorAll("#nav button").forEach((b) => b.classList.toggle("active", b.dataset.view === name));
    if (name === "camera") playCameras(); else stopCameras();
    if (name === "slides") startSlides(); else stopSlides();
    if (name === "settings") fillSettings();
    if (name === "events") renderEvents(); else closeEvents();
    lastTouch = Date.now();
    updateMode();
  }

  function showNav() {
    $("nav").classList.add("show");
    clearTimeout(navTimer);
    navTimer = setTimeout(() => $("nav").classList.remove("show"), cfg.views.buttons_hide_seconds * 1000);
  }

  function checkIdle() {
    if (REMOTE) return;
    const minutes = { camera: cfg.views.camera_timeout_minutes, weather: cfg.views.weather_timeout_minutes, settings: 10, events: 10 }[view];
    if (minutes && Date.now() - lastTouch > minutes * 60000) show("clock");
  }

  document.querySelectorAll("#nav button").forEach((b) => b.addEventListener("click",
    () => (REMOTE ? command({ view: b.dataset.view }) : show(b.dataset.view))));
  ["pointerdown", "keydown"].forEach((type) => document.addEventListener(type, () => {
    lastTouch = Date.now();
    showNav();
    updateMode();
  }));

  // --- camera view: video straight from the cameras (see rtsp.js) ------------
  // A tap on the video steps through: all cameras -> each camera alone -> all.
  let soloCam = -1;   // index of the camera shown alone, or -1 for all

  function buildCameras() {
    const host = $("cams");
    host.textContent = "";
    cfg.cameras.forEach((cam, index) => {
      if (!cam.host) return;
      const tile = el("figure", "cam");
      tile.dataset.cam = index;
      tile.append(el("canvas"), el("figcaption", "", cam.name), el("div", "cam-note"));
      host.appendChild(tile);
    });
    if (!host.children.length) host.textContent = "No cameras set";
    if (view === "camera") playCameras();
  }

  $("cams").addEventListener("click", () => {
    const steps = [-1].concat(Array.from(document.querySelectorAll(".cam"), (tile) => +tile.dataset.cam));
    soloCam = steps[(steps.indexOf(soloCam) + 1) % steps.length];
    playCameras();
  });

  function stopTile(tile) {
    clearTimeout(tile.retry);
    if (tile.stream) tile.stream.stop();
    tile.stream = null;
  }

  function playTile(tile) {
    if (tile.stream) return;   // already running
    const cam = cfg.cameras[tile.dataset.cam];
    const note = tile.querySelector(".cam-note");
    clearTimeout(tile.retry);
    note.textContent = "Connecting";
    tile.stream = new window.CameraStream(tile.querySelector("canvas"), {
      host: cam.host,
      user: cfg.camera_user,
      password: cfg.camera_password,
      subtype: cam.stream,
      onState: (state, message) => {
        if (state === "playing") { note.textContent = ""; return; }
        note.textContent = message;
        tile.stream = null;
        tile.retry = setTimeout(() => playTile(tile), 6000);
      },
    });
    tile.stream.start();
  }

  function playCameras() {
    const solo = soloCam >= 0;
    $("cams").classList.toggle("single", solo);
    $("view-camera").classList.toggle("single", solo);   // one camera fills the screen
    document.querySelectorAll(".cam").forEach((tile) => {
      const mine = +tile.dataset.cam === soloCam;
      tile.classList.toggle("full", mine);
      if (solo && !mine) stopTile(tile); else playTile(tile);
    });
  }

  function stopCameras() {
    soloCam = -1;
    document.querySelectorAll(".cam").forEach(stopTile);
  }

  // --- slideshow --------------------------------------------------------------
  // Pictures from the tablet's slides folder (filled by sync-slides.bat), played
  // from a shuffled deck: every picture once before any repeats. Two stacked
  // layers take turns so the leaving and arriving pictures animate against each
  // other; each picture drifts slowly while it holds, and a blurred copy of it
  // fills the bars around it.
  const SS_FX = {
    fade: { inn: [{ opacity: 0 }, { opacity: 1 }], out: [{ opacity: 1 }, { opacity: 0 }] },
    slide: {
      inn: [{ transform: "translateX(100%)" }, { transform: "none" }],
      out: [{ transform: "none", opacity: 1 }, { transform: "translateX(-30%)", opacity: 0 }],
    },
    zoom: {
      inn: [{ transform: "scale(1.15)", opacity: 0 }, { transform: "none", opacity: 1 }],
      out: [{ transform: "none", opacity: 1 }, { transform: "scale(0.94)", opacity: 0 }],
    },
  };
  const SS_TRANS_MS = 1100;
  const BACKLIGHT_RAMP_MS = 5000;   // Android changes the backlight this slowly
  const ss = { items: [], deck: [], layers: [], front: 0, last: null, timer: null, run: 0, count: 0, interlude: false, fading: false, fadeTimer: null };

  ss.layers = [0, 1].map(() => {
    const layer = { el: el("div", "ss-slide"), back: el("div", "ss-backdrop"), img: el("img", "ss-img"), drift: null };
    layer.el.append(layer.back, layer.img);
    $("ss-stage").appendChild(layer.el);
    return layer;
  });

  // The clock page shown between pictures (slides.clock_every); the mode stays Slides.
  function interlude(on) {
    ss.interlude = on;
    ss.fading = false;
    $("view-slides").hidden = on;
    $("view-clock").hidden = !on;
    updateMode();
  }

  // Auto run: the slideshow comes up when the set span begins and gives way to
  // the clock when it ends. It acts only at those two moments, so switching
  // views by hand in between is respected.
  let autoRunning = false;
  function checkSlideSchedule() {
    const s = cfg.slides;
    if (REMOTE || !s.auto_start || !s.auto_stop) return;
    const due = inSpan(s.auto_start, s.auto_stop, new Date());
    if (due === autoRunning || view === "settings") return;
    autoRunning = due;
    if (due) show("slides");
    else if (view === "slides") show("clock");
  }

  async function startSlides() {
    const run = ++ss.run;
    ss.count = 0;
    ss.interlude = false;
    ss.fading = false;
    $("ss-digits").hidden = !cfg.slides.clock;
    $("ss-empty").hidden = true;
    // Every time Slides is opened the folder is read again: its content may
    // have changed (sync-slides.bat). The pictures kept in memory are dropped,
    // and a new address makes sure each one really comes from the folder.
    if (native.clearCache) native.clearCache();
    const fresh = "?v=" + Date.now();
    let names = [];
    try { names = await native.listSlides(); } catch (e) { /* treated as an empty folder */ }
    if (run !== ss.run) return;
    ss.items = names.map((name) => "/slides/" + encodeURIComponent(name) + fresh);
    ss.deck = [];
    $("ss-empty").hidden = ss.items.length > 0;
    if (ss.items.length) nextSlide(true);
  }

  function stopSlides() {
    ss.run++;
    ss.interlude = false;
    ss.fading = false;
    clearTimeout(ss.fadeTimer);
    clearTimeout(ss.timer);
    ss.layers.forEach((layer) => {
      layer.el.getAnimations().forEach((a) => a.cancel());
      if (layer.drift) layer.drift.cancel();
      layer.el.style.opacity = 0;
      layer.img.removeAttribute("src");   // free the decoded pictures
      layer.back.style.backgroundImage = "";
    });
  }

  async function nextSlide(first) {
    const run = ss.run;
    if (!ss.deck.length) {
      ss.deck = ss.items.slice().sort(() => Math.random() - 0.5);
      if (ss.deck.length > 1 && ss.deck[ss.deck.length - 1] === ss.last) ss.deck.unshift(ss.deck.pop());   // no immediate repeat
    }
    const url = ss.last = ss.deck.pop();
    const hold = Math.max(3, cfg.slides.seconds) * 1000;
    const arriving = ss.layers[1 - ss.front], leaving = first ? null : ss.layers[ss.front];
    arriving.img.src = url;
    try {
      await arriving.img.decode();
    } catch (e) {   // unreadable picture: skip it
      if (run === ss.run) ss.timer = setTimeout(() => nextSlide(first), 1500);
      return;
    }
    if (run !== ss.run) return;
    arriving.back.style.backgroundImage = 'url("' + url + '")';
    arriving.el.getAnimations().forEach((a) => a.cancel());
    if (arriving.drift) arriving.drift.cancel();
    arriving.el.style.zIndex = 2;
    arriving.el.style.opacity = 1;
    if (leaving) leaving.el.style.zIndex = 1;

    const names = Object.keys(SS_FX);
    const fx = SS_FX[first ? "fade" : cfg.slides.transition] || SS_FX[names[Math.floor(Math.random() * names.length)]];
    const timing = { duration: SS_TRANS_MS, easing: "cubic-bezier(.22,.61,.36,1)", fill: "forwards" };
    arriving.el.animate(fx.inn, timing);
    if (leaving) leaving.el.animate(fx.out, timing);
    if (cfg.slides.motion) {   // slow pan and zoom while it holds
      const dx = (Math.random() * 3 - 1.5).toFixed(2), dy = (Math.random() * 2 - 1).toFixed(2);
      arriving.drift = arriving.img.animate(
        [{ transform: "scale(1)" }, { transform: "scale(1.08) translate(" + dx + "%," + dy + "%)" }],
        { duration: hold + SS_TRANS_MS * 2, fill: "forwards" });
    }
    ss.front = 1 - ss.front;
    ss.count++;
    if (ss.items.length < 2) return;
    const every = cfg.slides.clock_every;
    if (!(every > 0) || ss.count % every) {
      ss.timer = setTimeout(() => nextSlide(false), hold);
      return;
    }
    // The clock page is next. Its backlight differs from the slideshow's and
    // Android changes it slowly, so start the change under the last picture:
    // the clock then appears already at its own level instead of too bright.
    ss.fadeTimer = setTimeout(() => { ss.fading = true; updateMode(); }, Math.max(0, hold - BACKLIGHT_RAMP_MS));
    ss.timer = setTimeout(() => {
      interlude(true);
      ss.timer = setTimeout(() => { interlude(false); nextSlide(false); }, Math.max(3, cfg.slides.clock_seconds) * 1000);
    }, hold);
  }

  // --- weather: forecast from Open-Meteo, "now" from Environment Canada ------
  const WMO = {
    0: ["☀️", "Clear"], 1: ["🌤️", "Mostly clear"], 2: ["⛅", "Partly cloudy"],
    3: ["☁️", "Overcast"], 45: ["🌫️", "Fog"], 48: ["🌫️", "Freezing fog"],
    51: ["🌦️", "Light drizzle"], 53: ["🌦️", "Drizzle"], 55: ["🌧️", "Heavy drizzle"],
    56: ["🌧️", "Freezing drizzle"], 57: ["🌧️", "Freezing drizzle"],
    61: ["🌧️", "Light rain"], 63: ["🌧️", "Rain"], 65: ["🌧️", "Heavy rain"],
    66: ["🌧️", "Freezing rain"], 67: ["🌧️", "Freezing rain"],
    71: ["🌨️", "Light snow"], 73: ["🌨️", "Snow"], 75: ["❄️", "Heavy snow"],
    77: ["❄️", "Snow grains"], 80: ["🌦️", "Light showers"], 81: ["🌧️", "Showers"],
    82: ["⛈️", "Violent showers"], 85: ["🌨️", "Snow showers"], 86: ["❄️", "Heavy snow showers"],
    95: ["⛈️", "Thunderstorm"], 96: ["⛈️", "Thunderstorm, hail"], 99: ["⛈️", "Thunderstorm, hail"],
  };
  const MOON = "🌙";
  const WX_STALE_MS = 3 * 3600e3;   // an older "current" reading would mislead
  const OBSERVED_MAX_AGE_MS = 2 * 3600e3;
  let wx = readStore("kiosk-wx");
  let wxTimer = null;

  function sky(code, isDay) {
    const hit = WMO[code] || ["🌡️", ""];
    const emoji = isDay ? hit[0] : code <= 1 ? MOON : code === 2 ? WMO[3][0] : hit[0];   // no sun after dark
    return { emoji: emoji, text: hit[1] };
  }
  const deg = (v) => Math.round(v) + "°";

  async function locate() {
    const w = cfg.weather;
    if (!w.city) return null;
    const key = "kiosk-geo:" + w.city + "," + (w.country_code || "");
    const saved = readStore(key);
    if (saved) return saved;
    const r = await fetch("https://geocoding-api.open-meteo.com/v1/search?count=10&name=" + encodeURIComponent(w.city));
    const hits = (await r.json()).results || [];
    const cc = (w.country_code || "").toUpperCase();
    const hit = hits.find((h) => !cc || h.country_code === cc) || hits[0];
    if (!hit) return null;
    const place = { lat: hit.latitude, lon: hit.longitude, name: hit.name + (hit.admin1 ? ", " + hit.admin1 : "") };
    writeStore(key, place);
    return place;
  }

  // Canada: what a nearby Environment Canada station reports right now. The
  // forecast model behind Open-Meteo can say "cloudy" while it is raining.
  async function observed(place) {
    const box = [place.lon - 0.6, place.lat - 0.4, place.lon + 0.6, place.lat + 0.4].join(",");
    const r = await fetch("https://api.weather.gc.ca/collections/citypageweather-realtime/items?f=json&limit=80&bbox=" + box);
    const lang = cfg.locale.slice(0, 2) === "fr" ? "fr" : "en";
    const far = (f) => Math.hypot((f.geometry.coordinates[0] - place.lon) * 0.7, f.geometry.coordinates[1] - place.lat);
    const fresh = (f) => {
      const c = f.properties.currentConditions;
      return c && c.condition && c.timestamp && Date.now() - Date.parse(c.timestamp.en) < OBSERVED_MAX_AGE_MS;
    };
    const hit = ((await r.json()).features || []).filter(fresh).sort((a, b) => far(a) - far(b))[0];
    if (!hit) return null;
    const c = hit.properties.currentConditions;
    const value = (node) => (node && node.value && typeof node.value.en === "number" ? node.value.en : null);
    return {
      text: c.condition[lang], key: c.condition.en, at: Date.parse(c.timestamp.en), station: hit.properties.name[lang],
      temp: value(c.temperature), humidity: value(c.relativeHumidity), wind: value(c.wind && c.wind.speed),   // metric
    };
  }

  function observedEmoji(key, isDay) {
    const k = key.toLowerCase();
    if (/thunder/.test(k)) return WMO[95][0];
    if (/snow|flurr|ice pellet|hail/.test(k)) return WMO[73][0];
    if (/rain|drizzle|shower|precip/.test(k)) return WMO[63][0];
    if (/fog|mist|haze|smoke/.test(k)) return WMO[45][0];
    if (/partly|mainly|few/.test(k)) return sky(2, isDay).emoji;
    if (/cloud|overcast/.test(k)) return WMO[3][0];
    return sky(0, isDay).emoji;
  }

  async function loadWeather() {
    try {
      const place = await locate();
      if (!place) return;
      const imperial = cfg.weather.units === "imperial";
      const r = await fetch("https://api.open-meteo.com/v1/forecast?latitude=" + place.lat + "&longitude=" + place.lon
        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day"
        + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
        + "&forecast_days=7&forecast_hours=25&timezone=auto"
        + (imperial ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : ""));
      if (!r.ok) throw new Error(r.status);
      wx = { at: Date.now(), place: place.name, wind: imperial ? "mph" : "km/h", d: await r.json(), now: null };
      if ((cfg.weather.country_code || "").toUpperCase() === "CA") {
        wx.now = await observed(place).catch(() => null);
        if (wx.now && imperial) {
          if (wx.now.temp != null) wx.now.temp = wx.now.temp * 9 / 5 + 32;
          if (wx.now.wind != null) wx.now.wind *= 0.621;
        }
      }
      writeStore("kiosk-wx", wx);
    } catch (e) { /* offline: keep showing the last forecast */ }
    renderWeather();
  }

  // Broad kinds of weather; a move from one to another is worth announcing.
  const CHANGE_HOURS = 12;
  function kindOf(code) {
    if (code >= 95) return "storm";
    if ((code >= 71 && code <= 77) || code === 85 || code === 86) return "snow";
    if (code >= 51) return "rain";
    return code >= 2 ? "cloud" : "clear";
  }
  function observedKind(key) {
    const k = key.toLowerCase();
    if (/thunder/.test(k)) return "storm";
    if (/snow|flurr|ice pellet|hail/.test(k)) return "snow";
    if (/rain|drizzle|shower|precip/.test(k)) return "rain";
    return /cloud|overcast|fog|mist|haze|smoke/.test(k) ? "cloud" : "clear";
  }

  // The first change of kind in the coming hours that lasts at least two hours,
  // as { emoji, at: "HH:MM" }; null when the weather holds.
  function comingChange(nowKind, h) {
    const first = h.time.findIndex((t) => new Date(t).getTime() > Date.now());
    if (first < 0) return null;
    for (let i = first; i < Math.min(first + CHANGE_HOURS, h.time.length); i++) {
      const kind = kindOf(h.weather_code[i]);
      if (kind === nowKind) continue;
      if (i + 1 < h.time.length && kindOf(h.weather_code[i + 1]) !== kind) continue;   // a one-hour blip
      return { emoji: sky(h.weather_code[i], h.is_day[i]).emoji, at: h.time[i].slice(11, 16) };
    }
    return null;
  }

  function cell(when, emoji, temps, precip) {
    const node = el("div", "cell");
    node.append(el("div", "when", when), el("div", "emoji", emoji), temps,
      el("div", "p", precip >= 20 ? Math.round(precip) + "%" : ""));
    return node;
  }

  function renderWeather() {
    const line = $("clock-wx"), now = $("wx-now"), hours = $("wx-hours"), days = $("wx-days");
    [line, now, hours, days].forEach((node) => { node.textContent = ""; });
    $("wx-place").textContent = "";
    if (!wx || !wx.d || !wx.d.current) return;
    const d = wx.d, cur = d.current, day = d.daily;
    const obs = wx.now && Date.now() - wx.now.at < OBSERVED_MAX_AGE_MS ? wx.now : null;
    const pick = (seen, model) => (obs && seen != null ? seen : model);
    const s = obs ? { emoji: observedEmoji(obs.key, cur.is_day), text: obs.text } : sky(cur.weather_code, cur.is_day);
    const temp = pick(obs && obs.temp, cur.temperature_2m);
    const hhmm = (ms) => { const t = new Date(ms); return pad(t.getHours()) + ":" + pad(t.getMinutes()); };
    $("wx-place").textContent = wx.place + (obs ? " · observed at " + obs.station + " " + hhmm(obs.at) : "")
      + " · updated " + hhmm(wx.at);

    if (Date.now() - wx.at < WX_STALE_MS) {
      const change = comingChange(obs ? observedKind(obs.key) : kindOf(cur.weather_code), d.hourly);
      line.append(el("span", "emoji", s.emoji), el("span", "temp", deg(temp)));
      if (change) {   // a change of weather ahead replaces the text and the day's range
        line.append(el("span", "range", "→"), el("span", "emoji", change.emoji), el("span", "", change.at));
      } else {
        line.append(el("span", "", s.text),
          el("span", "range", "↑ " + deg(day.temperature_2m_max[0]) + "  ↓ " + deg(day.temperature_2m_min[0])));
      }
    }

    const metrics = el("dl", "wx-metrics");
    [["Feels like", deg(cur.apparent_temperature)],
     ["Wind", Math.round(pick(obs && obs.wind, cur.wind_speed_10m)) + " " + wx.wind],
     ["Humidity", Math.round(pick(obs && obs.humidity, cur.relative_humidity_2m)) + "%"],
     ["High / low", deg(day.temperature_2m_max[0]) + " / " + deg(day.temperature_2m_min[0])],
     ["Sunrise", day.sunrise[0].slice(11, 16)],
     ["Sunset", day.sunset[0].slice(11, 16)],
    ].forEach((m) => {
      const item = el("div");
      item.append(el("dt", "", m[0]), el("dd", "", m[1]));
      metrics.appendChild(item);
    });
    now.append(el("span", "emoji", s.emoji), el("span", "temp", deg(temp)), el("span", "desc", s.text), metrics);

    const h = d.hourly;
    for (let i = 1; i < h.time.length; i += 3) {   // the next 24 h in 3-hour steps
      const hs = sky(h.weather_code[i], h.is_day[i]);
      hours.appendChild(cell(h.time[i].slice(11, 16), hs.emoji, el("div", "t", deg(h.temperature_2m[i])),
        h.precipitation_probability[i]));
    }

    const dayName = new Intl.DateTimeFormat(cfg.locale, { weekday: "short" });
    day.time.forEach((date, i) => {
      const temps = el("div", "t", deg(day.temperature_2m_max[i]) + " ");
      temps.appendChild(el("small", "", deg(day.temperature_2m_min[i])));
      days.appendChild(cell(i ? dayName.format(new Date(date + "T12:00")) : "Today",
        sky(day.weather_code[i], 1).emoji, temps, day.precipitation_probability_max[i]));
    });
  }

  // --- camera alarms ---------------------------------------------------------
  // The native side reports each camera rule starting and stopping. When an
  // alarm rule starts, pictures are taken from that camera for as long as it
  // stays triggered and shown as they arrive.
  const cameraState = {};   // camera index -> { active: Set of running rules, alarm: the one being recorded, online }
  const alarm = { open: false, events: new Set(), seen: new Map(), dismissed: new Set(), pics: [], idx: -1, shownAt: 0, openedAt: 0 };

  function stateOf(index) {
    if (!cameraState[index]) cameraState[index] = { active: new Set(), alarm: null, online: true };
    return cameraState[index];
  }

  function onCameraStatus(status) {   // { cam, online }
    const state = stateOf(status.cam);
    state.online = status.online;
    if (!status.online) state.active.clear();
  }

  function onCameraEvent(event) {     // { cam, code, action, index }
    const cam = cfg.cameras[event.cam];
    if (!cam || (event.code !== TEST_CODE && !cfg.alarm.codes.includes(event.code))) return;
    const state = stateOf(event.cam);
    const rule = event.code + "#" + event.index;
    if (event.action === "Stop") { state.active.delete(rule); return; }
    if (event.action === "Start") state.active.add(rule);
    if (state.alarm) {                // already recording this camera: same alarm
      if (!state.alarm.codes.includes(event.code)) state.alarm.codes.push(event.code);
      return;
    }
    record(event.cam, cam, event.code);
  }

  async function record(index, cam, code) {
    const state = stateOf(index), a = cfg.alarm, now = new Date();
    const ev = {
      id: index + "-" + now.getTime(), cam: index, name: cam.name, codes: [code], active: true, pictures: [],
      time: pad(now.getHours()) + ":" + pad(now.getMinutes()) + ":" + pad(now.getSeconds()),
    };
    state.alarm = ev;
    if (a.sound) play(CAMERA_BEEP);
    onAlarm(ev);                      // the screen reacts before the first picture
    let failures = 0;
    while (ev.pictures.length < a.max_pictures && failures < 3 && !alarm.dismissed.has(ev.id)) {
      const started = Date.now();
      try {
        const r = await fetch("/cam/" + index + "/snapshot.jpg?t=" + started, { cache: "no-store" });
        if (!r.ok) throw new Error(r.status);
        const picture = await r.blob();
        ev.pictures.push(URL.createObjectURL(picture));
        keepShot(ev, picture);        // for the Events page; the alarm does not wait for it
        onAlarm(ev);
      } catch (e) { failures++; }
      if (!state.active.size && ev.pictures.length >= a.min_pictures) break;
      await sleep(Math.max(0, a.capture_interval_seconds * 1000 - (Date.now() - started)));
    }
    ev.active = false;
    state.alarm = null;
    onAlarm(ev);
    alarm.dismissed.delete(ev.id);
  }

  // Each picture is shown for max(min_picture_seconds, min_total_seconds / pictures):
  // one picture stays a full minute, more than six get ten seconds each.
  function onAlarm(ev) {
    if (alarm.dismissed.has(ev.id)) {
      ev.pictures.splice(0).forEach((url) => URL.revokeObjectURL(url));
      return;
    }
    if (!ev.active && !alarm.events.has(ev)) return;   // it ended after the screen had moved on
    const seen = alarm.seen.get(ev.id) || 0;
    alarm.seen.set(ev.id, ev.pictures.length);
    alarm.events.add(ev);
    ev.pictures.slice(seen).forEach((url) => alarm.pics.push({ url: url, ev: ev }));
    if (!alarm.open) {
      alarm.open = true;
      alarm.idx = -1;
      alarm.openedAt = Date.now();
      $("alarm-img").hidden = true;
      $("alarm").hidden = false;
      updateMode();
    }
    alarmTick();
    renderAlarm();
  }

  function renderAlarm() {
    const pic = alarm.pics[alarm.idx];
    const ev = pic ? pic.ev : Array.from(alarm.events).pop();
    if (!ev) return;
    syncAlarmLive(ev.cam);
    const label = labelOf(ev.codes);
    $("alarm-caption").textContent = ev.name + " · " + label + " · " + ev.time;
    $("alarm-count").textContent = pic ? (alarm.idx + 1) + " / " + alarm.pics.length : "";
  }

  // Upright tablet: the alarmed camera plays live under the pictures.
  let alarmLive = null;   // { cam, stream, retry } while it plays

  function syncAlarmLive(wanted) {
    const cam = alarm.open && upright.matches ? wanted : -1;
    if (alarmLive && alarmLive.cam === cam) return;
    if (alarmLive) {
      clearTimeout(alarmLive.retry);
      if (alarmLive.stream) alarmLive.stream.stop();
      alarmLive = null;
    }
    $("alarm").classList.toggle("with-live", cam >= 0);
    $("alarm-live-box").hidden = cam < 0;
    if (cam < 0) return;
    const live = alarmLive = { cam: cam, stream: null, retry: null };
    const camera = cfg.cameras[cam];
    const start = () => {
      live.stream = new window.CameraStream($("alarm-live"), {
        host: camera.host, user: cfg.camera_user, password: cfg.camera_password, subtype: camera.stream,
        onState: (state) => { if (state !== "playing" && alarmLive === live) live.retry = setTimeout(start, 4000); },
      });
      live.stream.start();
    };
    start();
  }
  upright.addEventListener("change", () => { if (alarm.open) renderAlarm(); });

  function showPicture(i) {
    alarm.idx = i;
    alarm.shownAt = Date.now();
    $("alarm-img").src = alarm.pics[i].url;
    $("alarm-img").hidden = false;
    renderAlarm();
  }

  function alarmTick() {
    if (!alarm.open) return;
    const n = alarm.pics.length, now = Date.now();
    const active = Array.from(alarm.events).some((ev) => ev.active);
    if (!n) {
      if (!active && now - alarm.openedAt > 5000) closeAlarm(false);   // the camera gave no picture
      return;
    }
    if (alarm.idx < 0) return showPicture(0);
    const each = Math.max(cfg.alarm.min_picture_seconds, cfg.alarm.min_total_seconds / n) * 1000;
    if (now - alarm.shownAt < each) return;
    if (alarm.idx < n - 1) showPicture(alarm.idx + 1);
    else if (!active) closeAlarm(false);
  }

  // Back to whatever view was showing; Cancel also drops the rest of this alarm.
  function closeAlarm(dismiss) {
    alarm.events.forEach((ev) => {
      if (dismiss) alarm.dismissed.add(ev.id);
      alarm.seen.delete(ev.id);
    });
    alarm.pics.forEach((pic) => URL.revokeObjectURL(pic.url));
    alarm.open = false;
    alarm.events.clear();
    alarm.pics = [];
    $("alarm").hidden = true;
    $("alarm-img").removeAttribute("src");
    syncAlarmLive(-1);
    lastTouch = Date.now();
    updateMode();
    if (view === "events") renderEvents();   // with what this alarm added
  }

  $("alarm-cancel").addEventListener("click", () => closeAlarm(true));

  function renderStatus() {
    const offline = cfg.cameras.filter((cam, i) => cam.host && cameraState[i] && !cameraState[i].online);
    $("status").textContent = offline.map((cam) => cam.name + " offline").join(" · ");
    $("status").hidden = !offline.length;
  }

  // --- camera events of the last 24 hours -------------------------------------
  // Every alarm picture is also kept on this device (IndexedDB), shrunk to
  // screen size plus a thumbnail, and dropped a day later. The Events page
  // lists the alarms, newest first; a tap on a picture opens it full screen.
  const HISTORY_MS = 24 * 3600 * 1000;
  const SHOT_WIDTH = 1280, THUMB_WIDTH = 360;
  let shotsOpening = null;

  function shotsDb() {
    if (!shotsOpening) {
      shotsOpening = new Promise((done, fail) => {
        const opening = indexedDB.open("wallclock", 1);
        opening.onupgradeneeded = () => opening.result.createObjectStore("shots", { autoIncrement: true }).createIndex("at", "at");
        opening.onsuccess = () => done(opening.result);
        opening.onerror = () => fail(opening.error);
      });
    }
    return shotsOpening;
  }

  function scaled(bitmap, width) {   // a JPEG of the picture, no wider than `width`
    const canvas = document.createElement("canvas");
    const scale = Math.min(1, width / bitmap.width);
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    const pen = canvas.getContext("2d");
    pen.imageSmoothingQuality = "high";
    pen.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return new Promise((done) => canvas.toBlob(done, "image/jpeg", 0.8));
  }

  async function keepShot(ev, picture) {
    try {
      const bitmap = await createImageBitmap(picture);
      const shot = {
        event: ev.id, at: Date.now(), cam: ev.cam, name: ev.name, codes: ev.codes.slice(),
        picture: (await scaled(bitmap, SHOT_WIDTH)) || picture, thumb: (await scaled(bitmap, THUMB_WIDTH)) || picture,
      };
      bitmap.close();
      const store = (await shotsDb()).transaction("shots", "readwrite").objectStore("shots");
      store.add(shot);
      store.index("at").openCursor(IDBKeyRange.upperBound(Date.now() - HISTORY_MS)).onsuccess = (found) => {
        const cursor = found.target.result;   // older than a day: gone
        if (cursor) { cursor.delete(); cursor.continue(); }
      };
      store.transaction.oncomplete = () => { if (view === "events" && !alarm.open) renderEvents(); };
    } catch (e) { /* no history on this device; the alarm itself is not affected */ }
  }

  async function loadEvents() {   // one list of pictures per alarm, the newest alarm first
    const index = (await shotsDb()).transaction("shots").objectStore("shots").index("at");
    const shots = await new Promise((done, fail) => {
      const asked = index.getAll(IDBKeyRange.lowerBound(Date.now() - HISTORY_MS));
      asked.onsuccess = () => done(asked.result);
      asked.onerror = () => fail(asked.error);
    });
    const byAlarm = new Map();
    shots.forEach((one) => byAlarm.set(one.event, (byAlarm.get(one.event) || []).concat(one)));
    return Array.from(byAlarm.values()).reverse();
  }

  const thumbUrls = [];   // object URLs of the thumbnails on the page
  const shot = { list: [], idx: 0, url: null };   // the picture opened full screen

  async function renderEvents() {
    let alarms = [];
    try { alarms = await loadEvents(); } catch (e) { /* no history on this device */ }
    if (view !== "events") return;
    const list = $("ev-list"), today = new Date().toDateString();
    thumbUrls.splice(0).forEach((url) => URL.revokeObjectURL(url));
    list.textContent = "";
    $("ev-empty").hidden = alarms.length > 0;
    $("ev-title").textContent = "Camera alarms \u00B7 last 24 hours" + (alarms.length ? " \u00B7 " + alarms.length : "");
    alarms.forEach((shots) => {
      const when = new Date(shots[0].at);
      const meta = el("div", "ev-meta");
      meta.append(
        el("div", "ev-time", pad(when.getHours()) + ":" + pad(when.getMinutes())),
        el("div", "ev-cam", shots[0].name),
        el("div", "ev-what", (when.toDateString() === today ? "" : "Yesterday \u00B7 ") + labelOf(shots[shots.length - 1].codes)));
      const strip = el("div", "ev-strip");
      shots.forEach((one, i) => {
        const img = el("img");
        img.alt = "";
        img.loading = "lazy";
        thumbUrls.push(img.src = URL.createObjectURL(one.thumb));
        img.addEventListener("click", () => openShot(shots, i));
        strip.appendChild(img);
      });
      const row = el("article", "ev");
      row.append(meta, strip);
      list.appendChild(row);
    });
  }

  function openShot(shots, i) {
    shot.list = shots;
    $("shot-prev").hidden = $("shot-next").hidden = shots.length < 2;
    $("shot").hidden = false;
    showShot(i);
  }

  function showShot(i) {   // steps round within the one alarm
    const n = shot.list.length;
    shot.idx = (i + n) % n;
    const one = shot.list[shot.idx], when = new Date(one.at);
    if (shot.url) URL.revokeObjectURL(shot.url);
    shot.url = URL.createObjectURL(one.picture);
    $("shot-img").src = shot.url;
    $("shot-caption").textContent = one.name + " \u00B7 " + labelOf(one.codes) + " \u00B7 "
      + pad(when.getHours()) + ":" + pad(when.getMinutes()) + ":" + pad(when.getSeconds());
    $("shot-count").textContent = (shot.idx + 1) + " / " + n;
  }

  function closeShot() {
    $("shot").hidden = true;
    $("shot-img").removeAttribute("src");
    if (shot.url) URL.revokeObjectURL(shot.url);
    shot.url = null;
    shot.list = [];
  }

  function closeEvents() {   // leaving the page: let go of its pictures
    closeShot();
    thumbUrls.splice(0).forEach((url) => URL.revokeObjectURL(url));
    $("ev-list").textContent = "";
  }

  $("shot-prev").addEventListener("click", () => showShot(shot.idx - 1));
  $("shot-next").addEventListener("click", () => showShot(shot.idx + 1));
  $("shot-close").addEventListener("click", closeShot);

  // --- alarm clock -------------------------------------------------------------
  // An alarm rings its sound in rounds: for wake.seconds, a pause, and so on for
  // wake.rounds rounds; then it gives up. Instead of the sound an alarm can play
  // a YouTube link, or music or a video from the media folder: over and over,
  // for wake.media_minutes. A picture from that folder is shown while the sound
  // rings. If music or video does not start, the sound rings as usual.
  const SNOOZE_MIN = 5;
  const ROUND_PAUSE_MS = 20000;
  const MEDIA_START_MS = 12000;   // music or video gets this long to start before the sound takes over
  const MEDIA_KINDS = {
    audio: ["mp3", "m4a", "aac", "ogg", "wav", "flac"],
    video: ["mp4", "m4v", "webm"],
    picture: ["jpg", "jpeg", "png", "webp", "gif"],
  };
  const YOUTUBE = "https://www.youtube.com";
  let ringing = false;
  let ringTimer = null;    // repeats the sound within a round
  let pauseTimer = null;   // waits between rounds
  let mediaTimer = null;   // waits for music or video to start, then ends it
  let lastRing = null;     // what rang last: Snooze rings it again
  let tube = null;         // the YouTube player while it is on: { frame, how, hello, playing }
  let rangAt = "";         // the minute that already rang, so it rings once
  let snoozeUntil = 0;

  function checkClockAlarms() {
    if (REMOTE || ringing) return;
    if (snoozeUntil && Date.now() >= snoozeUntil) { ring(); return; }
    const now = new Date();
    const hhmm = pad(now.getHours()) + ":" + pad(now.getMinutes());
    const minute = now.toDateString() + hhmm;
    if (minute === rangAt) return;
    const weekday = now.getDay() >= 1 && now.getDay() <= 5;
    const fits = { all: true, once: true, weekdays: weekday, weekend: !weekday };
    const due = cfg.clock_alarms.filter((a) => a.on && a.time === hhmm && fits[a.days]);
    if (!due.length) return;
    rangAt = minute;
    const once = due.filter((a) => a.days === "once");
    if (once.length) {   // a one-time alarm has done its job: it switches itself off
      once.forEach((a) => { a.on = false; });
      native.saveConfig(JSON.stringify(cfg));
    }
    ring(due[0]);
  }

  function mediaKind(name) {
    const ext = String(name || "").split(".").pop().toLowerCase();
    return Object.keys(MEDIA_KINDS).find((kind) => MEDIA_KINDS[kind].includes(ext)) || "";
  }

  function youtubeId(link) {
    const found = /(?:youtu\.be\/|[?&]v=|\/embed\/|\/shorts\/|\/live\/)([\w-]{11})/.exec(link || "");
    return found ? found[1] : "";
  }

  // how: an alarm's { sound, media, youtube }; what it leaves out comes from
  // wake. Without it, what rang last rings again (Snooze).
  function ring(how) {
    how = Object.assign({}, cfg.wake, how || lastRing);
    stopRinging(0);
    lastRing = how;
    ringing = true;
    const video = youtubeId(how.youtube), kind = video ? "youtube" : mediaKind(how.media);
    $("wake").classList.toggle("with-media", kind !== "" && kind !== "audio");
    $("wake").hidden = false;
    if (kind === "youtube") playYoutube(video, how);
    else if (kind === "audio" || kind === "video") playFile(kind, how);
    else {
      if (kind === "picture") {
        $("wake-img").src = "/media/" + encodeURIComponent(how.media);
        $("wake-img").hidden = false;
      }
      ringSound(how);
    }
    updateMode();
  }

  function ringSound(how) {
    const sound = SOUNDS[how.sound] || SOUNDS[cfg.wake.sound] || SOUNDS.chime;
    let round = 0;
    const startRound = () => {
      round++;
      const until = Date.now() + Math.max(2, how.seconds) * 1000;
      play(sound.notes);
      ringTimer = setInterval(() => {
        if (Date.now() < until) { play(sound.notes); return; }
        clearInterval(ringTimer);
        if (round >= how.rounds) stopRinging(0);
        else pauseTimer = setTimeout(startRound, ROUND_PAUSE_MS);
      }, sound.every * 1000);
    };
    clearTimeout(mediaTimer);
    clearInterval(ringTimer);
    clearTimeout(pauseTimer);
    startRound();
  }

  // Music or video is on: it goes round until Stop, Snooze or the time limit.
  // The sound falls silent if it had already taken over.
  function mediaStarted(how) {
    clearInterval(ringTimer);
    clearTimeout(pauseTimer);
    clearTimeout(mediaTimer);
    mediaTimer = setTimeout(() => stopRinging(0), Math.max(1, how.media_minutes) * 60000);
  }

  function playFile(kind, how) {
    const player = $("wake-video");
    player.hidden = kind !== "video";   // music plays unseen
    player.loop = true;
    player.onplaying = () => { player.onplaying = null; mediaStarted(how); };
    player.onerror = () => ringSound(how);
    player.src = "/media/" + encodeURIComponent(how.media);
    mediaTimer = setTimeout(() => ringSound(how), MEDIA_START_MS);
    player.play().catch(() => { if (ringing && lastRing === how && player.onplaying) ringSound(how); });
  }

  function playYoutube(id, how) {
    const frame = el("iframe");
    frame.allow = "autoplay; encrypted-media";
    frame.referrerPolicy = "strict-origin-when-cross-origin";
    frame.setAttribute("sandbox", "allow-scripts allow-same-origin allow-presentation");   // it cannot leave its frame
    frame.src = YOUTUBE + "/embed/" + id + "?autoplay=1&playsinline=1&loop=1&playlist=" + id
      + "&rel=0&enablejsapi=1&origin=" + encodeURIComponent(location.origin);
    const mine = tube = { frame: frame, how: how, hello: null, playing: false };
    // The player tells what it is doing once it has been spoken to; keep
    // speaking until it answers.
    frame.addEventListener("load", () => {
      mine.hello = setInterval(() => say(mine, { event: "listening" }), 500);
    });
    $("wake-media").appendChild(frame);
    mediaTimer = setTimeout(() => ringSound(how), MEDIA_START_MS);
  }

  function say(player, words) {
    if (!player.frame.contentWindow) return;
    player.frame.contentWindow.postMessage(JSON.stringify(Object.assign({ id: 1, channel: "widget" }, words)), YOUTUBE);
  }

  window.addEventListener("message", (message) => {
    if (!tube || message.origin !== YOUTUBE || message.source !== tube.frame.contentWindow) return;
    let said;
    try { said = JSON.parse(message.data); } catch (e) { return; }
    if (tube.hello) {   // it answers: ask to be told when it plays or fails
      clearInterval(tube.hello);
      tube.hello = null;
      ["onStateChange", "onError"].forEach((name) => say(tube, { event: "command", func: "addEventListener", args: [name] }));
    }
    const state = said.event === "onStateChange" ? said.info : said.info && said.info.playerState;
    if (said.event === "onError") ringSound(tube.how);   // not found, or not allowed outside YouTube
    else if (state === 1 && !tube.playing) { tube.playing = true; mediaStarted(tube.how); }
  });

  function stopRinging(snoozeMinutes) {
    clearInterval(ringTimer);
    clearTimeout(pauseTimer);
    clearTimeout(mediaTimer);
    const player = $("wake-video");
    player.onplaying = player.onerror = null;
    player.pause();
    player.removeAttribute("src");
    player.load();   // lets go of the file
    player.hidden = true;
    $("wake-img").hidden = true;
    $("wake-img").removeAttribute("src");
    if (tube) {
      clearInterval(tube.hello);
      tube.frame.remove();
      tube = null;
    }
    ringing = false;
    snoozeUntil = snoozeMinutes ? Date.now() + snoozeMinutes * 60000 : 0;
    $("wake").hidden = true;
    lastTouch = Date.now();
    updateMode();
  }

  $("wake-stop").addEventListener("click", () => stopRinging(0));
  $("wake-snooze").addEventListener("click", () => stopRinging(SNOOZE_MIN));

  // --- settings (one tab per subject) ------------------------------------------
  const STREAMS = [["0", "Main stream"], ["1", "Sub stream 1"], ["2", "Sub stream 2"]];
  const DAYS = [["all", "Every day"], ["weekdays", "Mon\u2013Fri"], ["weekend", "Sat\u2013Sun"], ["once", "Today, once"]];
  const SOUND_NAMES = Object.keys(SOUNDS).map((key) => [key, SOUNDS[key].name]);
  const HINTS = {
    Slideshow: "Pictures come from the slides folder on the PC: run sync-slides.bat, then tap Slides to load them.",
    "Alarm clock": "Music, videos and pictures come from the media folder on the PC: run sync-media.bat. "
      + "A YouTube link is played when given; a picture is shown while the sound rings.",
  };
  // Tabs of [setting, label, kind, conversion, button]; kind is an input type, a
  // list of choices, or "media" (a file from the media folder); button is
  // [label, action] for a button beside the field.
  const percent = { show: (v) => (v < 0 ? "" : Math.round(v * 100)), read: (s) => (s === "" ? -1 : Math.min(100, Math.max(0, +s)) / 100) };
  const number = { show: (v) => v, read: (s) => Math.max(0, +s || 0) };
  const SETTINGS = [
    ["General", [
      ["locale", "Date language", [["en-CA", "English"], ["fr-CA", "Fran\u00E7ais"], ["ru-RU", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"]]],
      ["views.buttons_hide_seconds", "Hide the buttons after, s", "number", number],
      ["remote_pin", "Remote control PIN (empty = remote control off)", "text"],
      ["night.by", "Night mode", [["time", "By the clock"], ["dark", "When the room is dark"]]],
      ["night.dark_lux", "Dark means below, lux", "number", number],
      ["night.start", "Night mode from", "time"],
      ["night.end", "Night mode until", "time"],
      ["night.brightness", "Night backlight, % (0 = the lowest the screen can do)", "number", percent],
      ["night.dim", "Night text strength, %", "number", percent],
      ["day.brightness", "Day backlight when not following the room, % (empty = Android decides)", "number", percent],
      ["day.auto.on", "Day backlight follows the room light", "checkbox"],
      ["day.auto.min", "…in the dark, %", "number", percent],
      ["day.auto.max", "…at its brightest and after a tap, %", "number", percent],
      ["day.auto.full_lux", "…brightest from, lux", "number", number],
      ["night.color", "Night colour", "color"],
      ["day.color", "Day colour", "color"],
      ["views.camera_timeout_minutes", "Camera view back to clock after, min (0 = never)", "number", number],
      ["views.weather_timeout_minutes", "Weather view back to clock after, min (0 = never)", "number", number],
    ]],
    ["Weather", [
      ["weather.city", "City", "text"],
      ["weather.country_code", "Country code", "text"],
      ["weather.units", "Units", [["metric", "\u00B0C, km/h"], ["imperial", "\u00B0F, mph"]]],
      ["weather.refresh_minutes", "Refresh every, min", "number", number],
    ]],
    ["Cameras", [
      ["camera_user", "Camera login", "text"],
      ["camera_password", "Password", "password"],
      ["cameras.0.name", "Camera 1 name", "text"],
      ["cameras.1.name", "Camera 2 name", "text"],
      ["cameras.0.host", "Camera 1 address", "text"],
      ["cameras.1.host", "Camera 2 address", "text"],
      ["cameras.0.stream", "Camera 1 video", STREAMS, number],
      ["cameras.1.stream", "Camera 2 video", STREAMS, number],
      ["alarm.min_total_seconds", "Alarm stays on screen at least, s", "number", number],
      ["alarm.min_picture_seconds", "Each alarm picture at least, s", "number", number],
      ["alarm.min_pictures", "Pictures per alarm, at least", "number", number],
      ["alarm.max_pictures", "Pictures per alarm, at most", "number", number],
      ["alarm.sound", "Short beep on a camera alarm", "checkbox"],
    ]],
    ["Slideshow", [
      ["slides.seconds", "Each picture, s", "number", number],
      ["slides.transition", "Transition", [["random", "Random"], ["fade", "Crossfade"], ["slide", "Slide"], ["zoom", "Zoom"]]],
      ["slides.motion", "Slow pan and zoom", "checkbox"],
      ["slides.brightness_day", "Backlight by day, % (empty = as usual)", "number", percent],
      ["slides.brightness_night", "Backlight at night, % (empty = as usual)", "number", percent],
      ["slides.clock_every", "Show the clock page after every N pictures (0 = never)", "number", number],
      ["slides.clock_seconds", "Clock page stays, s", "number", number],
      ["slides.clock", "Show the time", "checkbox"],
      ["slides.auto_start", "Start by itself at (empty = off)", "time"],
      ["slides.auto_stop", "Back to the clock at", "time"],
    ]],
    ["Alarm clock", [
      ["wake.seconds", "Sound rings for, s", "number", number],
      ["wake.rounds", "Repeat, times (20 s apart)", "number", number],
      ["wake.media_minutes", "Music or video plays for, min", "number", number],
    ].concat([0, 1, 2, 3].reduce((rows, i) => rows.concat([
      ["clock_alarms." + i + ".on", "Alarm " + (i + 1), "checkbox", null, ["Test", () => ringAsShown(i)]],
      ["clock_alarms." + i + ".time", "Time", "time"],
      ["clock_alarms." + i + ".days", "Days", DAYS],
      ["clock_alarms." + i + ".sound", "Sound", SOUND_NAMES],
      ["clock_alarms." + i + ".media", "Music, video or picture", "media"],
      ["clock_alarms." + i + ".youtube", "or a YouTube link", "text"],
    ]), [])), "three"],
  ];
  const FIELDS = SETTINGS.reduce((all, tab) => all.concat(tab[1]), []);
  let settingsTab = 0;

  function pathGet(object, path) {
    return path.split(".").reduce((node, key) => (node == null ? undefined : node[key]), object);
  }
  function pathSet(object, path, value) {
    const keys = path.split(".");
    const last = keys.pop();
    keys.reduce((node, key) => node[key], object)[last] = value;
  }

  function fillSettings() {
    const form = $("settings-form"), tabs = $("settings-tabs");
    form.textContent = "";
    tabs.textContent = "";
    const media = native.listMedia().catch(() => []);   // asked once for all the alarms
    SETTINGS.forEach((tab, index) => {
      const button = el("button", "", tab[0]);
      button.type = "button";
      button.addEventListener("click", () => { settingsTab = index; showSettingsTab(); });
      tabs.appendChild(button);
      const box = el("fieldset", tab[2] || "");
      tab[1].forEach((field) => {
        const path = field[0], kind = field[2], convert = field[3], action = field[4];
        const row = el("label", kind === "checkbox" ? "field check" : "field");
        const value = pathGet(cfg, path);
        let input;
        if (Array.isArray(kind) || kind === "media") {
          input = el("select");
          // A file is listed from the start if it is the chosen one, even when the folder no longer has it.
          const choices = kind === "media" ? [["", "None"]].concat(value ? [[value, value]] : []) : kind;
          choices.forEach((option) => { input.appendChild(el("option", "", option[1])).value = option[0]; });
          if (kind === "media") {
            media.then((names) => names.forEach((name) => { if (name !== value) input.appendChild(el("option", "", name)).value = name; }));
          }
        } else {
          input = el("input");
          input.type = kind;
          input.autocomplete = "off";
          input.spellcheck = false;
        }
        if (kind === "checkbox") input.checked = !!value;
        else input.value = convert ? convert.show(value) : value == null ? "" : value;
        input.dataset.path = path;
        if (REMOTE && kind === "color") input.addEventListener("input", () => sendPreview(path, input.value));
        if (REMOTE && (kind === "password" || path === "remote_pin")) input.placeholder = "unchanged";   // never sent out
        row.append(el("span", "", field[1]), input);
        if (action) {
          const button = el("button", "field-action", action[0]);
          button.type = "button";
          button.addEventListener("click", action[1]);
          row.appendChild(button);
        }
        box.appendChild(row);
      });
      form.appendChild(box);
    });
    showSettingsTab();
  }

  function showSettingsTab() {
    Array.from($("settings-tabs").children).forEach((b, i) => b.classList.toggle("active", i === settingsTab));
    Array.from($("settings-form").children).forEach((box, i) => { box.hidden = i !== settingsTab; });
    $("settings-hint").textContent = HINTS[SETTINGS[settingsTab][0]] || "";
    $("settings-test").hidden = SETTINGS[settingsTab][0] !== "Cameras";
    const address = native.remoteAddress ? native.remoteAddress() : "";
    $("settings-remote").textContent = !address ? ""
      : cfg.remote_pin ? "Remote control from a PC or phone on this Wi-Fi: " + address
      : "Remote control is off. Set a PIN on the General tab to use it from a PC or phone.";
  }

  // Helps to choose the "dark" limit: the sensor's reading, live, on the General tab.
  function showRoomLight() {
    const lux = view === "settings" && settingsTab === 0 && native.lux ? native.lux() : -1;
    $("settings-light").textContent = lux < 0 ? "" : "Room light now: " + Math.round(lux) + " lux";
  }

  async function saveSettings() {
    const next = JSON.parse(JSON.stringify(cfg));
    $("settings-form").querySelectorAll("[data-path]").forEach((input) => {
      const field = FIELDS.find((f) => f[0] === input.dataset.path);
      const text = input.value.trim();
      pathSet(next, field[0], field[2] === "checkbox" ? input.checked : field[3] ? field[3].read(text) : text);
    });
    await native.saveConfig(JSON.stringify(next));
    location.reload();
  }

  $("settings-save").addEventListener("click", saveSettings);
  $("settings-system").hidden = !native.openSystemSettings;
  $("settings-system").addEventListener("click", () => native.openSystemSettings());
  $("settings-restart").hidden = !REMOTE && !native.restart;
  $("settings-restart").addEventListener("click", () => (REMOTE ? command({ restart: true }) : native.restart()));
  function testAlarm(cam) {   // cam: position of the camera to pretend about; the first one if not given
    show("clock");
    const first = cfg.cameras.findIndex((camera) => camera.host);
    const chosen = cfg.cameras[cam] && cfg.cameras[cam].host ? cam : first;
    onCameraEvent({ cam: chosen, code: TEST_CODE, action: "Pulse", index: 0 });
  }
  $("settings-test").addEventListener("click", () => (REMOTE ? command({ test: true }) : testAlarm()));
  // Test one alarm with what the form shows now, saved or not.
  function ringAsShown(i) {
    const shown = (path) => $("settings-form").querySelector('[data-path="' + path + '"]').value.trim();
    const alarm = "clock_alarms." + i + ".";
    const how = {
      sound: shown(alarm + "sound"), media: shown(alarm + "media"), youtube: shown(alarm + "youtube"),
      seconds: +shown("wake.seconds"), rounds: +shown("wake.rounds"), media_minutes: +shown("wake.media_minutes"),
    };
    if (REMOTE) command({ ring: how }); else ring(how);
  }
  $("settings-stop").hidden = !REMOTE;
  $("settings-stop").addEventListener("click", () => command({ stop: true }));

  // --- start ----------------------------------------------------------------------
  async function start() {
    try {
      const saved = await native.getConfig();
      if (saved) cfg = merge(BASE, JSON.parse(saved));
    } catch (e) { /* first run, or unreadable settings: defaults */ }
    // Alarms saved before each had its own sound and media get them now.
    cfg.clock_alarms = cfg.clock_alarms.map((a) => Object.assign({ media: "", youtube: "" }, a, { sound: a.sound || cfg.wake.sound }));
    const root = document.documentElement.style;
    root.setProperty("--night", cfg.night.color);
    root.setProperty("--day", cfg.day.color);
    root.setProperty("--night-dim", cfg.night.dim > 0 ? cfg.night.dim : 1);   // never invisible
    native.configure(JSON.stringify(cfg));   // the native side starts listening to the cameras
    if (view === "settings") fillSettings();
    buildCameras();
    renderClock(true);
    updateMode();
    wxTimer = setInterval(loadWeather, Math.max(5, cfg.weather.refresh_minutes) * 60000);
    loadWeather();
  }

  renderClock(true);
  renderWeather();
  show(REMOTE ? "settings" : "clock");
  setInterval(() => {
    renderClock(false);
    updateMode();
    alarmTick();
    checkClockAlarms();
    checkSlideSchedule();
    showRoomLight();
    checkIdle();
    renderStatus();
  }, 500);
  window.addEventListener("online", loadWeather);
  start();
})();
