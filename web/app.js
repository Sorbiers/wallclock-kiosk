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
  const VIEWS = ["clock", "camera", "weather", "slides", "settings"];
  const DAY_PEEK_MS = 5000;   // a tap at night shows day mode for this long
  const TEST_CODE = "Test";
  const EVENT_LABELS = {
    CrossLineDetection: "Line crossed",
    CrossRegionDetection: "Zone intrusion",
    Test: "Test alarm",
  };

  const DEFAULTS = {
    locale: "en-CA",
    weather: { city: "", country_code: "", units: "metric", refresh_minutes: 15 },
    night: { start: "22:00", end: "07:00", color: "#8f5cff", dim: 0.45, brightness: 0.03 },
    day: { brightness: -1, color: "#ffffff" },   // brightness -1 = leave the backlight to Android
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
    wake: { sound: "chime", seconds: 30, rounds: 3 },   // how the alarm clock rings
    clock_alarms: [0, 1, 2, 3].map(() => ({ on: false, time: "07:00", days: "all" })),
    camera_user: "",
    camera_password: "",
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
    remoteAddress: () => window.Android.remoteAddress(),
  } : REMOTE ? {
    getConfig: () => fetch("/api/config").then((r) => r.text()),
    saveConfig: (json) => fetch("/api/config", { method: "POST", body: json }),
    configure: () => {},
    setBrightness: () => {},
    listSlides: async () => [],
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
  };
  window.kioskNative = { event: onCameraEvent, status: onCameraStatus, back: () => show("clock"), remote: onRemote };

  // The remote page sends commands; the tablet carries them out.
  function command(what) {
    return fetch("/api/command", { method: "POST", body: JSON.stringify(what) });
  }
  function onRemote(what) {
    if (what.view && VIEWS.includes(what.view)) show(what.view);
    if (what.test) testAlarm();
    if (what.ring) ring(what.ring);
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
  const clocks = ["clock", "cam", "wx", "ss"].map((name) => buildDigits($(name + "-digits")));
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
    return inSpan(cfg.night.start, cfg.night.end, now);
  }

  // Is `now` between two "HH:MM" times? The span may run past midnight.
  function inSpan(from, until, now) {
    const mins = (hhmm) => { const p = hhmm.split(":"); return +p[0] * 60 + +p[1]; };
    const t = now.getHours() * 60 + now.getMinutes();
    const start = mins(from), end = mins(until);
    return start <= end ? t >= start && t < end : t >= start || t < end;
  }

  // Night mode (dim violet, low backlight) gives way to day mode whenever the
  // screen is being used: settings, an alarm, or the seconds after a tap.
  function updateMode() {
    const inUse = view === "settings" || alarm.open || ringing || Date.now() - lastTouch < DAY_PEEK_MS;
    night = isNight(new Date()) && !inUse;
    document.body.classList.toggle("night", night);
    const slideLight = night ? cfg.slides.brightness_night : cfg.slides.brightness_day;   // the slideshow's own backlight
    const level = view === "slides" && !ss.interlude && slideLight >= 0 ? slideLight
      : night ? cfg.night.brightness : cfg.day.brightness;
    if (level === lastBrightness) return;
    lastBrightness = level;
    native.setBrightness(level);
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
    ["clock", "cam", "wx"].forEach((name) => { $(name + "-date").textContent = weekday + " \u00B7 " + date; });
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
    const minutes = { camera: cfg.views.camera_timeout_minutes, weather: cfg.views.weather_timeout_minutes, settings: 10 }[view];
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
  const ss = { items: [], deck: [], layers: [], front: 0, last: null, timer: null, run: 0, count: 0, interlude: false };

  ss.layers = [0, 1].map(() => {
    const layer = { el: el("div", "ss-slide"), back: el("div", "ss-backdrop"), img: el("img", "ss-img"), drift: null };
    layer.el.append(layer.back, layer.img);
    $("ss-stage").appendChild(layer.el);
    return layer;
  });

  // The clock page shown between pictures (slides.clock_every); the mode stays Slides.
  function interlude(on) {
    ss.interlude = on;
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
    $("ss-digits").hidden = !cfg.slides.clock;
    $("ss-empty").hidden = true;
    let names = [];
    try { names = await native.listSlides(); } catch (e) { /* treated as an empty folder */ }
    if (run !== ss.run) return;
    ss.items = names.map((name) => "/slides/" + encodeURIComponent(name));
    ss.deck = [];
    $("ss-empty").hidden = ss.items.length > 0;
    if (ss.items.length) nextSlide(true);
  }

  function stopSlides() {
    ss.run++;
    ss.interlude = false;
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
    ss.timer = setTimeout(() => {
      const every = cfg.slides.clock_every;
      if (!(every > 0) || ss.count % every) { nextSlide(false); return; }
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
      line.append(el("span", "emoji", s.emoji), el("span", "temp", deg(temp)), el("span", "", s.text),
        el("span", "range", "↑ " + deg(day.temperature_2m_max[0]) + "  ↓ " + deg(day.temperature_2m_min[0])));
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
      id: index + "-" + now.getTime(), name: cam.name, codes: [code], active: true, pictures: [],
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
        ev.pictures.push(URL.createObjectURL(await r.blob()));
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
    const label = ev.codes.map((code) => EVENT_LABELS[code] || code).join(" + ");
    $("alarm-caption").textContent = ev.name + " · " + label + " · " + ev.time;
    $("alarm-count").textContent = pic ? (alarm.idx + 1) + " / " + alarm.pics.length : "";
  }

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
    lastTouch = Date.now();
    updateMode();
  }

  $("alarm-cancel").addEventListener("click", () => closeAlarm(true));

  function renderStatus() {
    const offline = cfg.cameras.filter((cam, i) => cam.host && cameraState[i] && !cameraState[i].online);
    $("status").textContent = offline.map((cam) => cam.name + " offline").join(" · ");
    $("status").hidden = !offline.length;
  }

  // --- alarm clock -------------------------------------------------------------
  // It rings in rounds: the chosen sound for wake.seconds, a pause, and so on
  // for wake.rounds rounds; then it gives up.
  const SNOOZE_MIN = 5;
  const ROUND_PAUSE_MS = 20000;
  let ringing = false;
  let ringTimer = null;    // repeats the sound within a round
  let pauseTimer = null;   // waits between rounds
  let rangAt = "";         // the minute that already rang, so it rings once
  let snoozeUntil = 0;

  function checkClockAlarms() {
    if (ringing) return;
    if (snoozeUntil && Date.now() >= snoozeUntil) { ring(); return; }
    const now = new Date();
    const hhmm = pad(now.getHours()) + ":" + pad(now.getMinutes());
    const minute = now.toDateString() + hhmm;
    if (minute === rangAt) return;
    const weekday = now.getDay() >= 1 && now.getDay() <= 5;
    const due = cfg.clock_alarms.some((a) => a.on && a.time === hhmm
      && (a.days === "all" || (a.days === "weekdays" ? weekday : !weekday)));
    if (!due) return;
    rangAt = minute;
    ring();
  }

  function ring(how) {   // how: { sound, seconds, rounds }; the saved settings unless testing
    how = how || cfg.wake;
    const sound = SOUNDS[how.sound] || SOUNDS.chime;
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
    stopRinging(0);
    ringing = true;
    $("wake").hidden = false;
    startRound();
    updateMode();
  }

  function stopRinging(snoozeMinutes) {
    clearInterval(ringTimer);
    clearTimeout(pauseTimer);
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
  const DAYS = [["all", "Every day"], ["weekdays", "Mon\u2013Fri"], ["weekend", "Sat\u2013Sun"]];
  const percent = { show: (v) => (v < 0 ? "" : Math.round(v * 100)), read: (s) => (s === "" ? -1 : Math.min(100, Math.max(1, +s)) / 100) };
  const number = { show: (v) => v, read: (s) => Math.max(0, +s || 0) };
  // Tabs of [setting, label, kind, conversion]; kind is an input type or a list of choices.
  const SETTINGS = [
    ["General", [
      ["locale", "Date language", [["en-CA", "English"], ["fr-CA", "Fran\u00E7ais"], ["ru-RU", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"]]],
      ["views.buttons_hide_seconds", "Hide the buttons after, s", "number", number],
      ["night.start", "Night mode from", "time"],
      ["night.end", "Night mode until", "time"],
      ["night.brightness", "Night backlight, %", "number", percent],
      ["day.brightness", "Day backlight, % (empty = automatic)", "number", percent],
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
      ["wake.sound", "Sound", Object.keys(SOUNDS).map((key) => [key, SOUNDS[key].name])],
      ["wake.seconds", "Ring for, s", "number", number],
      ["wake.rounds", "Repeat, times (20 s apart)", "number", number],
    ].concat([0, 1, 2, 3].reduce((rows, i) => rows.concat([
      ["clock_alarms." + i + ".on", "Alarm " + (i + 1), "checkbox"],
      ["clock_alarms." + i + ".time", "Time", "time"],
      ["clock_alarms." + i + ".days", "Days", DAYS],
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
    SETTINGS.forEach((tab, index) => {
      const button = el("button", "", tab[0]);
      button.type = "button";
      button.addEventListener("click", () => { settingsTab = index; showSettingsTab(); });
      tabs.appendChild(button);
      const box = el("fieldset", tab[2] || "");
      tab[1].forEach((field) => {
        const path = field[0], kind = field[2], convert = field[3];
        const row = el("label", kind === "checkbox" ? "field check" : "field");
        let input;
        if (Array.isArray(kind)) {
          input = el("select");
          kind.forEach((option) => { input.appendChild(el("option", "", option[1])).value = option[0]; });
        } else {
          input = el("input");
          input.type = kind;
          input.autocomplete = "off";
          input.spellcheck = false;
        }
        const value = pathGet(cfg, path);
        if (kind === "checkbox") input.checked = !!value;
        else input.value = convert ? convert.show(value) : value == null ? "" : value;
        input.dataset.path = path;
        if (REMOTE && kind === "password") input.placeholder = "unchanged";   // the tablet never sends it out
        row.append(el("span", "", field[1]), input);
        box.appendChild(row);
      });
      form.appendChild(box);
    });
    showSettingsTab();
  }

  function showSettingsTab() {
    Array.from($("settings-tabs").children).forEach((b, i) => b.classList.toggle("active", i === settingsTab));
    Array.from($("settings-form").children).forEach((box, i) => { box.hidden = i !== settingsTab; });
    $("settings-hint").hidden = SETTINGS[settingsTab][0] !== "Slideshow";
    $("settings-test").hidden = SETTINGS[settingsTab][0] !== "Cameras";
    $("settings-ring").hidden = SETTINGS[settingsTab][0] !== "Alarm clock";
    const address = native.remoteAddress ? native.remoteAddress() : "";
    $("settings-remote").textContent = address ? "Remote control from a PC or phone on this Wi-Fi: " + address : "";
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
  function testAlarm() {
    show("clock");
    onCameraEvent({ cam: cfg.cameras.findIndex((cam) => cam.host), code: TEST_CODE, action: "Pulse", index: 0 });
  }
  $("settings-test").addEventListener("click", () => (REMOTE ? command({ test: true }) : testAlarm()));
  // Test the alarm clock with what the form shows now, saved or not.
  $("settings-ring").addEventListener("click", () => {
    const pickFrom = (path) => $("settings-form").querySelector('[data-path="' + path + '"]').value;
    const how = { sound: pickFrom("wake.sound"), seconds: +pickFrom("wake.seconds"), rounds: +pickFrom("wake.rounds") };
    if (REMOTE) command({ ring: how }); else ring(how);
  });
  $("settings-stop").hidden = !REMOTE;
  $("settings-stop").addEventListener("click", () => command({ stop: true }));

  // --- start ----------------------------------------------------------------------
  async function start() {
    try {
      const saved = await native.getConfig();
      if (saved) cfg = merge(BASE, JSON.parse(saved));
    } catch (e) { /* first run, or unreadable settings: defaults */ }
    const root = document.documentElement.style;
    root.setProperty("--night", cfg.night.color);
    root.setProperty("--day", cfg.day.color);
    root.setProperty("--night-dim", cfg.night.dim);
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
    checkIdle();
    renderStatus();
  }, 500);
  window.addEventListener("online", loadWeather);
  start();
})();
