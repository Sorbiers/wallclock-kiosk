/* Live video straight from an Amcrest/Dahua camera, no server in between:
   RTSP tunnelled through the camera's own WebSocket (/rtspoverwebsocket),
   H.264 decoded with WebCodecs and painted on a canvas.

     const stream = new CameraStream(canvas, { host, user, password, subtype, onState });
     stream.start();  ...  stream.stop();

   onState(state, message): "playing" on the first picture, "error" when it gives up
   (the caller decides whether to try again). */
(function () {
  "use strict";

  // --- MD5, for the RTSP digest login (WebCrypto has no MD5) -----------------
  const MD5_K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296));
  const MD5_S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];

  function md5(text) {
    const bytes = new TextEncoder().encode(text);
    const words = new Uint32Array((((bytes.length + 8) >> 6) + 1) * 16);
    bytes.forEach((b, i) => { words[i >> 2] |= b << ((i % 4) * 8); });
    words[bytes.length >> 2] |= 0x80 << ((bytes.length % 4) * 8);
    words[words.length - 2] = bytes.length * 8;
    let a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;
    for (let block = 0; block < words.length; block += 16) {
      let A = a, B = b, C = c, D = d;
      for (let i = 0; i < 64; i++) {
        let f, g;
        if (i < 16) { f = (B & C) | (~B & D); g = i; }
        else if (i < 32) { f = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
        else if (i < 48) { f = B ^ C ^ D; g = (3 * i + 5) % 16; }
        else { f = C ^ (B | ~D); g = (7 * i) % 16; }
        const x = (A + f + MD5_K[i] + words[block + g]) | 0;
        const s = MD5_S[(i >> 4) * 4 + (i % 4)];
        A = D; D = C; C = B;
        B = (B + ((x << s) | (x >>> (32 - s)))) | 0;
      }
      a = (a + A) | 0; b = (b + B) | 0; c = (c + C) | 0; d = (d + D) | 0;
    }
    return [a, b, c, d].map((n) =>
      [0, 8, 16, 24].map((shift) => ((n >>> shift) & 255).toString(16).padStart(2, "0")).join("")).join("");
  }

  const text = new TextDecoder("latin1");
  const hex2 = (n) => n.toString(16).padStart(2, "0");
  const START_CODE = [0, 0, 0, 1];
  const KEEPALIVE_MS = 25000;   // the camera drops a silent session after 60 s
  const STALL_MS = 12000;       // no picture for this long = give up
  const MAX_QUEUE = 12;         // frames waiting to decode before we skip ahead

  function concat(parts) {
    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let at = 0;
    parts.forEach((p) => { out.set(p, at); at += p.length; });
    return out;
  }

  class CameraStream {
    constructor(canvas, options) {
      this.canvas = canvas;
      this.ctx = canvas.getContext("2d");
      this.o = options;
      this.url = "rtsp://" + options.host + ":554/cam/realmonitor?channel=1&subtype=" + options.subtype;
      this.cseq = 0;
      this.waiting = [];     // resolvers for the RTSP replies we are owed, in order
      this.rest = null;      // bytes of a message that ended mid-packet
      this.nals = [];        // NAL units of the picture being received
      this.fragments = null; // pieces of a NAL unit split across packets (FU-A)
      this.closed = false;
    }

    start() {
      if (!("VideoDecoder" in window)) return this._fail("This browser cannot decode video");
      this.ws = new WebSocket("ws://" + this.o.host + "/rtspoverwebsocket");
      this.ws.binaryType = "arraybuffer";
      this.ws.onopen = () => this._play().catch((e) => this._fail(e.message));
      this.ws.onmessage = (e) => this._feed(new Uint8Array(e.data));
      this.ws.onerror = () => this._fail("Camera not reachable");
      this.ws.onclose = () => this._fail("Camera closed the connection");
      this._watch();
    }

    stop() {
      if (this.closed) return;
      this.closed = true;
      clearInterval(this.keepalive);
      clearTimeout(this.watchdog);
      try { if (this.session) this._send("TEARDOWN"); } catch (e) {}
      try { this.ws.close(); } catch (e) {}
      try { if (this.decoder && this.decoder.state !== "closed") this.decoder.close(); } catch (e) {}
    }

    _fail(message) {
      if (this.closed) return;
      this.stop();
      this.o.onState("error", message);
    }

    _watch() {
      clearTimeout(this.watchdog);
      this.watchdog = setTimeout(() => this._fail("No video from the camera"), STALL_MS);
    }

    // --- RTSP ----------------------------------------------------------------
    _send(method, target, extra) {
      target = target || this.url;
      const lines = [method + " " + target + " RTSP/1.0", "CSeq: " + (++this.cseq), "User-Agent: wallclock"];
      if (this.auth) {
        const ha1 = md5(this.o.user + ":" + this.auth.realm + ":" + this.o.password);
        const response = md5(ha1 + ":" + this.auth.nonce + ":" + md5(method + ":" + target));
        lines.push('Authorization: Digest username="' + this.o.user + '", realm="' + this.auth.realm
          + '", nonce="' + this.auth.nonce + '", uri="' + target + '", response="' + response + '"');
      }
      if (this.session) lines.push("Session: " + this.session);
      this.ws.send(lines.concat(extra || []).join("\r\n") + "\r\n\r\n");
    }

    _request(method, target, extra) {
      this._send(method, target, extra);
      return new Promise((resolve) => this.waiting.push(resolve));
    }

    async _play() {
      let reply = await this._request("DESCRIBE", null, ["Accept: application/sdp"]);
      if (reply.status === 401) {
        const challenge = /realm="([^"]*)"[^]*?nonce="([^"]*)"/.exec(reply.head);
        if (!challenge || !this.o.user) throw new Error("Camera login is not set");
        this.auth = { realm: challenge[1], nonce: challenge[2] };
        reply = await this._request("DESCRIBE", null, ["Accept: application/sdp"]);
      }
      if (reply.status === 401) throw new Error("Camera refused the login");
      if (reply.status === 404) throw new Error("This stream is switched off on the camera");
      if (reply.status !== 200) throw new Error("Camera answered " + reply.status);

      const video = (reply.body.split(/\r?\nm=/).find((part, i) => i > 0 && part.startsWith("video")) || "");
      if (!/rtpmap:\d+ H264/i.test(video)) {
        throw new Error(/rtpmap:\d+ (\w+)/.test(video) ? "Stream is " + RegExp.$1 + ", needs H.264" : "This stream is switched off on the camera");
      }
      const sprop = /sprop-parameter-sets=([^;\s]+)/.exec(video);
      if (sprop) {   // SPS and PPS, in case the camera does not repeat them in the stream
        this.params = sprop[1].split(",").map((b64) => Uint8Array.from(atob(b64), (ch) => ch.charCodeAt(0)));
      }
      const control = (/a=control:(\S+)/.exec(video) || [])[1] || "trackID=0";
      const base = (/Content-Base:\s*(\S+)/i.exec(reply.head) || [])[1] || this.url + "/";
      reply = await this._request("SETUP", /^rtsp:/.test(control) ? control : base + control,
        ["Transport: RTP/AVP/TCP;unicast;interleaved=0-1"]);
      if (reply.status !== 200) throw new Error("Camera refused the stream (" + reply.status + ")");
      this.session = (/Session:\s*([^;\r\n]+)/i.exec(reply.head) || [])[1];
      reply = await this._request("PLAY", null, ["Range: npt=0.000-"]);
      if (reply.status !== 200) throw new Error("Camera would not play (" + reply.status + ")");
      this.keepalive = setInterval(() => this._request("GET_PARAMETER"), KEEPALIVE_MS);
    }

    // The socket carries RTSP replies (text) mixed with "$"-framed media packets.
    _feed(chunk) {
      const buf = this.rest ? concat([this.rest, chunk]) : chunk;
      let at = 0;
      while (at < buf.length) {
        if (buf[at] === 0x24) {
          if (buf.length - at < 4) break;
          const size = (buf[at + 2] << 8) | buf[at + 3];
          if (buf.length - at < 4 + size) break;
          if (buf[at + 1] === 0) this._packet(buf.subarray(at + 4, at + 4 + size));
          at += 4 + size;
        } else {
          let end = -1;
          for (let i = at; i + 3 < buf.length; i++) {
            if (buf[i] === 13 && buf[i + 1] === 10 && buf[i + 2] === 13 && buf[i + 3] === 10) { end = i; break; }
          }
          if (end < 0) break;
          const head = text.decode(buf.subarray(at, end));
          const length = +((/Content-Length:\s*(\d+)/i.exec(head) || [])[1] || 0);
          if (buf.length < end + 4 + length) break;
          const reply = {
            status: +((/^RTSP\/1\.0 (\d+)/.exec(head) || [])[1] || 0),
            head: head,
            body: text.decode(buf.subarray(end + 4, end + 4 + length)),
          };
          at = end + 4 + length;
          const resolve = this.waiting.shift();
          if (resolve) resolve(reply);
        }
      }
      this.rest = at < buf.length ? buf.slice(at) : null;
    }

    // --- RTP -> H.264 pictures (RFC 6184) ---------------------------------------
    _packet(p) {
      if (p.length < 13) return;
      let at = 12 + (p[0] & 0x0f) * 4;
      if (p[0] & 0x10) at += 4 + ((p[at + 2] << 8) | p[at + 3]) * 4;   // header extension
      const end = p[0] & 0x20 ? p.length - p[p.length - 1] : p.length;  // padding
      if (at >= end) return;
      const kind = p[at] & 0x1f;
      if (kind >= 1 && kind <= 23) {
        this._nal(p.subarray(at, end));
      } else if (kind === 24) {            // several small units in one packet
        for (let i = at + 1; i + 2 <= end;) {
          const size = (p[i] << 8) | p[i + 1];
          this._nal(p.subarray(i + 2, i + 2 + size));
          i += 2 + size;
        }
      } else if (kind === 28) {            // one big unit across several packets
        const part = p[at + 1];
        if (part & 0x80) this.fragments = [Uint8Array.of((p[at] & 0xe0) | (part & 0x1f))];
        if (this.fragments) {
          this.fragments.push(p.subarray(at + 2, end));
          if (part & 0x40) { this._nal(concat(this.fragments)); this.fragments = null; }
        }
      }
      if (p[1] & 0x80) {                   // marker: the picture is complete
        this._picture(((p[4] << 24) | (p[5] << 16) | (p[6] << 8) | p[7]) >>> 0);
      }
    }

    _nal(unit) {
      if (!unit.length) return;
      const kind = unit[0] & 0x1f;
      if (kind === 7) this.sps = unit.slice();
      if (kind === 8) this.pps = unit.slice();
      if (kind === 5) this.key = true;
      this.nals.push(unit);
    }

    _picture(rtpTime) {
      let units = this.nals;
      const key = this.key;
      this.nals = [];
      this.key = false;
      if (!units.length || this.closed) return;
      if (!this.sps && this.params) { this.sps = this.params[0]; this.pps = this.params[1]; }
      if (!this.decoder) {
        if (!key || !this.sps) return;     // decoding can only start at a key picture
        this.decoder = new VideoDecoder({
          output: (frame) => this._draw(frame),
          error: (e) => this._fail("Cannot decode this stream (" + e.message + ")"),
        });
        this.decoder.configure({
          codec: "avc1." + hex2(this.sps[1]) + hex2(this.sps[2]) + hex2(this.sps[3]),
          optimizeForLatency: true,
        });
      }
      if (this.skipping && !key) return;
      this.skipping = this.decoder.decodeQueueSize > MAX_QUEUE;   // fell behind: resume at the next key picture
      if (this.skipping) return;
      if (key && this.pps && !units.some((u) => (u[0] & 0x1f) === 7)) units = [this.sps, this.pps].concat(units);
      const parts = [];
      units.forEach((u) => { parts.push(START_CODE, u); });
      this.decoder.decode(new EncodedVideoChunk({
        type: key ? "key" : "delta",
        timestamp: Math.round(rtpTime * 1000 / 90),   // 90 kHz clock -> microseconds
        data: concat(parts),
      }));
    }

    _draw(frame) {
      if (!this.closed) {
        if (this.canvas.width !== frame.displayWidth || this.canvas.height !== frame.displayHeight) {
          this.canvas.width = frame.displayWidth;
          this.canvas.height = frame.displayHeight;
        }
        this.ctx.drawImage(frame, 0, 0);
        if (!this.playing) { this.playing = true; this.o.onState("playing"); }
        this._watch();
      }
      frame.close();
    }
  }

  window.CameraStream = CameraStream;
  window.CameraStream.md5 = md5;   // exposed for a self-check
})();
