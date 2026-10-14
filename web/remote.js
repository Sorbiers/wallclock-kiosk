// The remote control: shows what the wall clock is doing, switches its page,
// stops its alarms, and lists the camera alarms with the pictures the cameras took and the videos they recorded. It talks to the
// tablet through /api/.... Signing in with name and PIN gives a session, which
// every request then carries; the tablet ends it after a day, or after eight
// hours unused, and this browser keeps no PIN. The settings are another page: /settings.

(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const pad = (n) => String(n).padStart(2, "0");
  const POLL_MS = 2000;
  const THUMBS_AT_ONCE = 3;
  const VIDEO_ASK_MS = 700;   // how often a video on its way to the tablet is asked after
  const RULES = { CrossLineDetection: "Line crossed", CrossRegionDetection: "Zone intrusion" };
  const ruleNames = (codes) => codes.map((code) => RULES[code] || code).join(" + ");

  let tablet = null;      // the wall clock's last report
  let asking = false;     // the sign-in form is up: no asking the tablet meanwhile, a wrong PIN repeated locks it
  let waitUntil = 0;      // locked out until then
  let events = [];        // the camera alarms, while their list is open
  let urls = [];          // object URLs of the pictures on the page
  let shown = null;       // { event, index } of the picture opened large
  let film = null;        // { file, url } of the video opened large

  const el = (tag, cls, text) => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  };

  // --- talking to the tablet ---------------------------------------------------
  localStorage.removeItem("kiosk-pin");    // kept here by earlier versions; no more
  localStorage.removeItem("kiosk-user");   // a user is known by the PIN alone now
  function signedAs() {
    return { "X-Session": localStorage.getItem("kiosk-session") || "" };
  }

  // The tablet's answer, or null when there is none to use (it then says why on the display).
  async function call(path, body) {
    let reply;
    try {
      reply = await fetch(path, body == null ? { headers: signedAs() } : { method: "POST", headers: signedAs(), body: body });
    } catch (e) {
      lost("No answer from the wall clock");
      return null;
    }
    if (reply.ok) return reply;
    if (reply.status === 401) {
      const had = localStorage.getItem("kiosk-session");
      localStorage.removeItem("kiosk-session");
      signIn(had ? "Signed out: a sign-in lasts a day, or eight hours unused" : "");
    }
    else if (reply.status === 429) { waitUntil = Date.now() + 60000; lost("Too many wrong PINs. Wait a minute."); }
    else if (reply.status === 403) lost((await reply.text()) ? "This network is not allowed in the wall clock's settings" : "The remote control is off: no user is listed in the wall clock's settings");
    else lost("The wall clock did not answer that");
    return null;
  }

  function lost(why) {
    tablet = null;
    $("led").className = "led";
    $("state").textContent = why;
    $("weather").textContent = "";
    $("alert").hidden = true;
    $("controls").hidden = true;
  }

  function signIn(note) {
    asking = true;
    lost("Sign in to use the remote");
    $("signin-note").textContent = note;
    $("signin-note").className = note ? "bad" : "";
    $("signin-pin").value = "";
    $("signin").hidden = false;
  }

  $("signin").addEventListener("submit", async (submit) => {
    submit.preventDefault();
    const pin = $("signin-pin").value.trim();
    let reply = null;
    try {
      reply = await fetch("/api/session", { method: "POST", headers: { "X-Pin": pin } });
    } catch (e) { /* said below */ }
    if (!reply || !reply.ok) {
      const why = !reply ? "No answer from the wall clock" : reply.status === 401 ? "Wrong PIN"
        : reply.status === 429 ? "Too many wrong PINs. Wait a minute." : reply.status === 403 ? "Not allowed from here" : "The wall clock did not answer that";
      signIn(why);
      return;
    }
    localStorage.setItem("kiosk-session", (await reply.json()).session);
    asking = false;
    $("signin").hidden = true;
    $("state").textContent = "Calling the wall clock";
    look();
  });

  $("signout").addEventListener("click", () => {
    const session = localStorage.getItem("kiosk-session");
    localStorage.removeItem("kiosk-session");
    if (session) fetch("/api/session/end", { method: "POST", headers: { "X-Session": session } }).catch(() => {});
    signIn("");
  });

  async function look() {
    if (asking || Date.now() < waitUntil || document.visibilityState !== "visible") return;
    const reply = await call("/api/state");
    if (!reply) return;
    const told = await reply.json();
    tablet = told.tablet && told.tablet.view ? told.tablet : null;
    $("who").textContent = told.user;
    $("open-settings").hidden = !told.settings;   // only for a user allowed the settings
    show();
  }

  async function send(command) {
    if (await call("/api/command", JSON.stringify(command))) setTimeout(look, 400);   // the display follows at once
  }

  // --- the display and the keys ---------------------------------------------------
  function showTime() {
    const now = new Date(), locale = tablet && tablet.locale ? tablet.locale : undefined;
    $("time").textContent = pad(now.getHours()) + ":" + pad(now.getMinutes());
    $("date").textContent = new Intl.DateTimeFormat(locale, { weekday: "long", day: "numeric", month: "long" }).format(now);
  }

  function show() {
    showTime();
    if (!tablet) { lost("The wall clock has not said what it is doing"); return; }
    const busy = tablet.ringing || tablet.alarm;
    $("controls").hidden = false;
    $("led").className = busy ? "led alert" : "led on";
    $("weather").textContent = tablet.weather || "";

    const state = $("state");
    state.textContent = "";
    state.append("Showing ", el("b", "", tablet.view), " · " + (tablet.night ? "night" : "day"));
    if (tablet.printer) state.append(" · " + tablet.printer);
    document.querySelector('#pad [data-view="printer"]').hidden = $("open-printer").hidden = !tablet.printerOn;
    (tablet.offline || []).forEach((camera) => state.append(" · ", el("span", "bad", camera + " offline")));
    if (tablet.snoozed && !tablet.ringing) state.append(" · alarm snoozed");

    $("alert").hidden = !busy;
    $("alert").textContent = tablet.ringing ? "Alarm clock ringing" : tablet.alarm ? "Camera alarm · " + (tablet.alarmText || "") : "";
    $("alarm-row").hidden = !busy;
    $("stop").textContent = tablet.ringing ? "Stop" : "Cancel";
    $("snooze").hidden = !tablet.ringing;
    document.querySelectorAll("#pad .key").forEach((key) => key.classList.toggle("on", key.dataset.view === tablet.view));
  }

  document.querySelectorAll("#pad .key").forEach((key) => key.addEventListener("click", () => send({ view: key.dataset.view })));
  $("stop").addEventListener("click", () => send({ stop: true }));
  $("snooze").addEventListener("click", () => send({ snooze: true }));
  $("open-settings").addEventListener("click", () => { location.href = "settings"; });

  // --- the printer: what it is doing, and its camera's picture, both asked again every few seconds ---
  const PRINTER_MS = 3000;
  const PRINT_WORDS = { IDLE: "idle", PREPARE: "preparing", RUNNING: "printing", PAUSE: "paused", FINISH: "finished", FAILED: "failed", SLICING: "slicing" };
  let printerTimer = null;
  let printerPicture = "";

  function openPrinter() {
    $("handset").hidden = true;
    $("printer").hidden = false;
    $("printer-facts").textContent = "Asking the wall clock";
    lookAtPrinter();
  }

  async function lookAtPrinter() {
    clearTimeout(printerTimer);
    if ($("printer").hidden) return;
    const reply = await call("/api/printer");
    if ($("printer").hidden) return;
    if (reply) {
      const s = await reply.json(), lines = [];
      const hm = (minutes) => Math.floor(minutes / 60) + ":" + pad(minutes % 60);
      const heat = (now, target) => Math.round(now) + "\u00B0" + (target > 0 ? " \u2192 " + Math.round(target) + "\u00B0" : "");
      $("printer-state").textContent = !s.online ? "not reachable" : PRINT_WORDS[s.gcode_state] || "";
      if (s.online) {
        if (s.subtask_name) lines.push("Job: " + s.subtask_name.replace(/(\.gcode)?\.3mf$|\.gcode$/i, ""));
        if (/^(PREPARE|RUNNING|PAUSE|FINISH)$/.test(s.gcode_state || "")) {
          lines.push("Progress: " + (s.mc_percent || 0) + " %" + (s.total_layer_num ? " · layer " + s.layer_num + " / " + s.total_layer_num : ""));
        }
        if (/^(PREPARE|RUNNING|PAUSE)$/.test(s.gcode_state || "") && s.mc_remaining_time > 0) lines.push("Time left: " + hm(s.mc_remaining_time));
        if (s.nozzle_temper != null) lines.push("Nozzle " + heat(s.nozzle_temper, s.nozzle_target_temper) + " · bed " + heat(s.bed_temper, s.bed_target_temper));
        if (s.print_error) lines.push("Error code " + Number(s.print_error).toString(16).toUpperCase() + ": see the printer's screen");
        if (s.hms && s.hms.length) lines.push(s.hms.length + " message(s) on the printer's screen");
      }
      $("printer-facts").textContent = lines.join("\n") || (s.online ? "" : "The wall clock has no word from the printer.");
    }
    try {   // the picture: fetched with the session, as the alarm pictures are
      const picture = await fetch("/api/printer/camera", { headers: signedAs() });
      if (picture.ok && !$("printer").hidden) {
        const url = URL.createObjectURL(await picture.blob());
        $("printer-cam").src = url;
        if (printerPicture) URL.revokeObjectURL(printerPicture);
        printerPicture = url;
      }
    } catch (e) { /* the next round may do better */ }
    printerTimer = setTimeout(lookAtPrinter, PRINTER_MS);
  }

  function closePrinter() {
    clearTimeout(printerTimer);
    $("printer").hidden = true;
    $("handset").hidden = false;
    $("printer-cam").removeAttribute("src");
    if (printerPicture) URL.revokeObjectURL(printerPicture);
    printerPicture = "";
  }

  $("open-printer").addEventListener("click", openPrinter);
  $("printer-back").addEventListener("click", closePrinter);

  // --- the list of camera alarms ---------------------------------------------------
  // The pictures are the cameras' own, copied to the tablet. Each is fetched with the name and PIN, a few at a time.
  async function picture(shot, size) {
    const reply = await call("/api/shot?size=" + size + "&file=" + encodeURIComponent(shot.file));
    if (!reply) return "";
    const url = URL.createObjectURL(await reply.blob());
    urls.push(url);
    return url;
  }

  async function openEvents() {
    $("handset").hidden = true;
    $("events").hidden = false;
    $("events-list").textContent = "";
    $("events-note").textContent = "Asking the wall clock";
    const reply = await call("/api/events");
    const told = reply ? await reply.json() : { alarms: [], failed: [] };
    events = told.alarms;
    $("events-note").textContent = !reply ? "The wall clock did not send the list."
      : (events.length ? "" : "No camera alarms in the last 24 hours. ") + (told.failed.length ? "No answer from " + told.failed.join(", ") + "." : "");
    const today = new Date().toDateString(), waiting = [];
    events.forEach((event) => {
      const when = new Date(event.at), about = el("div"), shots = el("div", "event-shots");
      about.append(el("div", "event-time", pad(when.getHours()) + ":" + pad(when.getMinutes())), el("div", "event-cam", event.name),
        el("div", "event-what", (when.toDateString() === today ? "" : "Yesterday · ") + ruleNames(event.codes)));
      event.shots.forEach((shot, index) => {
        const key = el("button", "shot-key"), img = el("img");
        key.type = "button";
        img.alt = "";
        key.appendChild(img);
        key.addEventListener("click", () => openShot(event, index));
        shots.appendChild(key);
        waiting.push([shot, img]);
      });
      const row = el("article", "event");
      row.append(about, shots);
      if (event.videos && event.videos.length) {
        const videos = el("div", "event-videos");
        event.videos.forEach((one) => {
          const at = new Date(one.at), several = event.videos.length > 1;
          const key = el("button", "key video-key", "\u25B6 " + (several ? "Video " + pad(at.getHours()) + ":" + pad(at.getMinutes()) : "Watch video")
            + " \u00B7 " + lasting(one.seconds));
          key.type = "button";
          key.addEventListener("click", () => openVideo(event, one));
          videos.appendChild(key);
        });
        row.appendChild(videos);
      }
      $("events-list").appendChild(row);
    });
    const list = events;   // the thumbnails come in a few at a time, top of the list first
    const worker = async () => {
      for (let next = waiting.shift(); next && events === list && !$("events").hidden; next = waiting.shift()) {
        next[1].src = await picture(next[0], "thumb");
      }
    };
    for (let i = 0; i < THUMBS_AT_ONCE; i++) worker();
  }

  function closeEvents() {
    closeShot();
    closeVideo();
    $("events").hidden = true;
    $("handset").hidden = false;
    $("events-list").textContent = "";
    events = [];
    urls.splice(0).forEach((url) => URL.revokeObjectURL(url));
  }

  async function openShot(event, index) {
    const count = event.shots.length;
    shown = { event: event, index: (index + count) % count };
    const shot = event.shots[shown.index], when = new Date(shot.at), mine = shown;
    $("lightbox-caption").textContent = event.name + " · " + ruleNames(event.codes) + " · "
      + pad(when.getHours()) + ":" + pad(when.getMinutes()) + ":" + pad(when.getSeconds());
    $("lightbox-count").textContent = (shown.index + 1) + " / " + count;
    $("lightbox-prev").hidden = $("lightbox-next").hidden = count < 2;
    $("lightbox").hidden = false;
    const url = await picture(shot, "full");
    if (shown === mine && url) $("lightbox-img").src = url;
  }

  function closeShot() {
    shown = null;
    $("lightbox").hidden = true;
    $("lightbox-img").removeAttribute("src");
  }

  // A camera's video of an alarm. It is on the camera's card at first: the
  // tablet copies it over when asked (asked again until that is done), then
  // this browser fetches it whole from the tablet, with the name and PIN, and plays it.
  const lasting = (seconds) => Math.floor(seconds / 60) + ":" + pad(seconds % 60);

  async function openVideo(event, one) {
    closeVideo();
    const mine = film = { file: one.file, url: "" }, when = new Date(one.at);
    const note = (text) => { if (film === mine) $("player-note").textContent = text; };
    const share = (got, size) => (size > 0 ? " \u00B7 " + Math.min(99, Math.round(100 * got / size)) + " %" : "");
    $("player-caption").textContent = event.name + " \u00B7 " + ruleNames(event.codes) + " \u00B7 "
      + pad(when.getHours()) + ":" + pad(when.getMinutes()) + ":" + pad(when.getSeconds()) + " \u00B7 " + lasting(one.seconds);
    $("player-video").hidden = true;
    $("player").hidden = false;
    note("Asking the wall clock for the video");
    const address = "?file=" + encodeURIComponent(one.file);
    for (;;) {   // the tablet fetches it from the camera
      const reply = await call("/api/clip/get" + address);
      if (film !== mine) return;
      const told = reply ? await reply.json() : { failed: "The wall clock did not answer." };
      if (told.failed != null) { note(told.failed || "The video could not be fetched."); return; }
      if (told.done) break;
      note("The wall clock is copying the video from the camera" + share(told.got, told.size));
      await new Promise((go) => setTimeout(go, VIDEO_ASK_MS));
      if (film !== mine) return;
    }
    note("Fetching the video from the wall clock");
    const reply = await call("/api/clip" + address);
    if (film !== mine) return;
    if (!reply) { note("The wall clock did not send the video."); return; }
    const size = +reply.headers.get("Content-Length") || 0, pieces = [], reader = reply.body.getReader();
    try {
      for (let got = 0; ;) {
        const piece = await reader.read();
        if (film !== mine) { reader.cancel(); return; }
        if (piece.done) break;
        pieces.push(piece.value);
        got += piece.value.length;
        note("Fetching the video from the wall clock" + share(got, size));
      }
    } catch (e) {
      note("The video did not arrive whole.");
      return;
    }
    mine.url = URL.createObjectURL(new Blob(pieces, { type: "video/mp4" }));
    note("");
    $("player-video").hidden = false;
    $("player-video").src = mine.url;
    $("player-video").play().catch(() => { /* its own play button remains */ });
  }

  function closeVideo() {
    const video = $("player-video"), was = film;
    film = null;
    video.pause();
    if (video.hasAttribute("src")) {
      video.removeAttribute("src");
      video.load();
    }
    if (was && was.url) URL.revokeObjectURL(was.url);
    $("player").hidden = true;
  }

  $("player-video").addEventListener("error", () => {
    if (!film || !film.url) return;
    $("player-video").hidden = true;
    $("player-note").textContent = "This browser cannot play the video.";
  });
  $("player-close").addEventListener("click", closeVideo);

  $("open-events").addEventListener("click", openEvents);
  $("events-back").addEventListener("click", closeEvents);
  $("lightbox-close").addEventListener("click", closeShot);
  $("lightbox-prev").addEventListener("click", () => openShot(shown.event, shown.index - 1));
  $("lightbox-next").addEventListener("click", () => openShot(shown.event, shown.index + 1));
  document.addEventListener("keydown", (key) => {
    if (film && key.key === "Escape") closeVideo();
    if (!shown) return;
    if (key.key === "Escape") closeShot();
    if (key.key === "ArrowLeft") openShot(shown.event, shown.index - 1);
    if (key.key === "ArrowRight") openShot(shown.event, shown.index + 1);
  });

  // --- start ---------------------------------------------------------------------------
  showTime();
  setInterval(showTime, 1000);
  setInterval(look, POLL_MS);
  document.addEventListener("visibilitychange", look);
  look();
})();
