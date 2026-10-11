// The remote control: shows what the wall clock is doing, switches its page,
// stops its alarms, and lists the camera alarms with the pictures the cameras took. It talks to the
// tablet through /api/..., every request carrying the user's name and PIN
// (kept in this browser). The settings are another page: /settings.

(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const pad = (n) => String(n).padStart(2, "0");
  const POLL_MS = 2000;
  const THUMBS_AT_ONCE = 3;
  const RULES = { CrossLineDetection: "Line crossed", CrossRegionDetection: "Zone intrusion" };
  const ruleNames = (codes) => codes.map((code) => RULES[code] || code).join(" + ");

  let tablet = null;      // the wall clock's last report
  let asking = false;     // the sign-in form is up: no asking the tablet meanwhile, a wrong PIN repeated locks it
  let waitUntil = 0;      // locked out until then
  let events = [];        // the camera alarms, while their list is open
  let urls = [];          // object URLs of the pictures on the page
  let shown = null;       // { event, index } of the picture opened large

  const el = (tag, cls, text) => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  };

  // --- talking to the tablet ---------------------------------------------------
  function signedAs() {
    return { "X-User": encodeURIComponent(localStorage.getItem("kiosk-user") || "home"), "X-Pin": localStorage.getItem("kiosk-pin") || "" };
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
    if (reply.status === 401) signIn(localStorage.getItem("kiosk-pin") ? "Wrong name or PIN" : "");
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
    $("signin-user").value = localStorage.getItem("kiosk-user") || "";
    $("signin-pin").value = "";
    $("signin").hidden = false;
  }

  $("signin").addEventListener("submit", (submit) => {
    submit.preventDefault();
    localStorage.setItem("kiosk-user", $("signin-user").value.trim());
    localStorage.setItem("kiosk-pin", $("signin-pin").value.trim());
    asking = false;
    $("signin").hidden = true;
    $("state").textContent = "Calling the wall clock";
    look();
  });

  $("signout").addEventListener("click", () => {
    localStorage.removeItem("kiosk-pin");
    signIn("");
  });

  async function look() {
    if (asking || Date.now() < waitUntil || document.visibilityState !== "visible") return;
    const reply = await call("/api/state");
    if (!reply) return;
    const told = await reply.json();
    tablet = told.tablet && told.tablet.view ? told.tablet : null;
    $("who").textContent = told.user;
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

  $("open-events").addEventListener("click", openEvents);
  $("events-back").addEventListener("click", closeEvents);
  $("lightbox-close").addEventListener("click", closeShot);
  $("lightbox-prev").addEventListener("click", () => openShot(shown.event, shown.index - 1));
  $("lightbox-next").addEventListener("click", () => openShot(shown.event, shown.index + 1));
  document.addEventListener("keydown", (key) => {
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
