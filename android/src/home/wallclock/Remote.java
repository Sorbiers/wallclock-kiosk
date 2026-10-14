package home.wallclock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Remote control: a tiny web server on the tablet. http://tablet:8080 (or the
 * port the settings name) from a PC or phone shows a page made like a TV
 * remote (remote.html): what the wall clock is doing, buttons to switch its
 * page, the camera alarms with the pictures the cameras took and the videos
 * they recorded.
 * "/settings" is the kiosk page itself in "remote" form, with every setting.
 *
 * Who gets in: only addresses on the networks the settings allow (the tablet's
 * own /24, and others named there, such as a VPN's), and under "/api" only the
 * users listed there, each known by a PIN of their own; a user's name is only
 * for showing. A browser signs in once with its PIN (header X-Pin, to
 * "/api/session") and is given a session (header X-Session), which ends after
 * a day, or after eight hours unused; the PIN is not kept in the browser.
 * Scripts may send the PIN with every request instead. Only users allowed the
 * settings get them ("/api/config"). A few wrong PINs lock it for a minute. No
 * PIN and no camera password is ever sent out.
 *
 * Nothing here needs Android, so these rules are tested on a PC
 * (tools/test_remote.py).
 */
final class Remote extends Thread {
    /** Who may use the remote control. Read from the settings at every request. */
    static final class Access {
        boolean lan = true;                                   // the tablet's own /24
        String also = "";                                     // more networks: "10.8.0.0/24, 192.168.50.7"
        final Map<String, User> users = new HashMap<>();      // PIN -> who it is
    }

    /** A user of the remote control: a name to show, and whether the settings are theirs to change. */
    static final class User {
        final String name;
        final boolean settings;

        User(String name, boolean settings) {
            this.name = name;
            this.settings = settings;
        }
    }

    /** What the server needs from the app. */
    interface Host {
        Access access();

        /** A file of the page, as bundled with the app. */
        InputStream asset(String name) throws IOException;

        /** The settings as JSON, without anything secret. */
        String config();

        void saveConfig(String json);

        void command(String json);

        /** The names of the files the alarm clock can play, as a JSON list. */
        String media();

        /** The names of the folders inside the slides folder, as a JSON list. */
        String slideFolders();

        /** What the tablet is doing right now, as JSON. */
        String state();

        /** The camera alarms of the last day, as JSON (see Cameras.alarms). */
        String alarms();

        /** What the printer is doing, as JSON (see Printer.state). */
        String printer();

        /** The printer camera's latest picture, as JPEG bytes; null when there is none. */
        byte[] printerPicture();

        /** One of the tablet's copies of the cameras' alarm pictures, small or in full, as JPEG bytes; null when there is none. */
        byte[] shot(String file, boolean small);

        /** A camera's video of an alarm is wanted: the app copies it to the tablet. JSON telling how far that is (see Cameras.clipState). */
        String clipState(String file);

        /** The tablet's copy of that video, once it is here whole; else null. */
        File clip(String file);

        /** A secret of this tablet's own, the same from one start to the next: it signs the sessions. */
        String secret();

        void log(String message);
    }

    static final int DEFAULT_PORT = 8080;   // the settings may name another
    /** How the tablet names its copies of the cameras' alarm pictures (see Cameras). Nothing else is handed out. */
    static final String SHOT_FILE = "\\d+_\\d+_[\\w+]*_[0-9A-F]+\\.jpg";
    /** ...and its copies of their videos of an alarm. */
    static final String CLIP_FILE = "\\d+_\\d+_\\d+_[0-9A-F]+\\.mp4";
    private static final int MAX_BODY = 200000;
    private static final int MAX_TRIES = 5;
    private static final long LOCK_MS = 60000;
    private static final int MAX_VISITORS = 24;   // at once; more than a household's browsers need
    static final long SESSION_MS = 24 * 3600000L;     // a sign-in lasts a day at most
    static final long UNUSED_MS = 8 * 3600000L;       // ...and ends sooner when not used for this long

    private final int port;
    private final Host host;
    private final AtomicInteger visitors = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile boolean busy;
    private int wrongPins;
    private long lockedUntil;
    // When each session was last used, since this start; 0 = signed out. A session not
    // here yet (made before a restart) counts as used at its first visit after it.
    private final Map<String, Long> used = new ConcurrentHashMap<>();
    LongSupplier clock = System::currentTimeMillis;   // a test sets its own

    Remote(int port, Host host) {
        super("remote");
        this.port = port;
        this.host = host;
    }

    int port() {
        return port;
    }

    /** True when the port could not be opened: something else on the tablet has it. */
    boolean busy() {
        return busy;
    }

    void close() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket(port);
        } catch (IOException taken) {
            busy = true;
            host.log("Remote control not available on port " + port + ": " + taken);
            return;
        }
        while (!server.isClosed()) {
            try {
                final Socket visitor = server.accept();
                if (visitors.incrementAndGet() > MAX_VISITORS) {
                    visitors.decrementAndGet();
                    visitor.close();
                    continue;
                }
                new Thread(() -> {
                    try {
                        serve(visitor);
                    } finally {
                        visitors.decrementAndGet();
                    }
                }).start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket visitor) {
        try (Socket socket = visitor) {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            Access access = host.access();
            if (!allowed(socket.getInetAddress(), socket.getLocalAddress(), access)) {
                send(out, 403, "text/plain", "Not from this network.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String request = line(in);   // "GET /path?query HTTP/1.1"
            if (request == null) return;
            String[] parts = request.split(" ");
            int length = 0;
            String pin = null, session = null;
            for (String header = line(in); header != null && !header.isEmpty(); header = line(in)) {
                String lower = header.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
                if (lower.startsWith("x-pin:")) pin = header.substring(6).trim();
                if (lower.startsWith("x-session:")) session = header.substring(10).trim();
            }
            byte[] body = new byte[Math.max(0, Math.min(length, MAX_BODY))];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            String[] target = (parts.length > 1 ? parts[1] : "/").split("\\?", 2);
            answer(out, parts[0], target[0], target.length > 1 ? target[1] : "", access, pin, session,
                    new String(body, 0, got, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failed) {
            host.log("Remote request failed: " + failed);
        }
    }

    private void answer(OutputStream out, String method, String path, String query, Access access,
                        String pin, String session, String body) throws IOException {
        boolean post = method.equals("POST");
        User who = null;
        if (path.startsWith("/api/")) {
            boolean bySession = !path.equals("/api/session") && session != null && !session.isEmpty();   // signing in is by PIN only
            who = bySession ? member(access, session) : null;
            int refusal = who != null ? 0 : bySession ? (access.users.isEmpty() ? 403 : 401) : refusal(access, pin);
            if (refusal != 0) {
                send(out, refusal, "text/plain", new byte[0]);
                return;
            }
            if (who == null) who = access.users.get(pin.trim());
            if (path.equals("/api/config") && !who.settings) {   // this user may look and switch pages, not change the settings
                send(out, 403, "text/plain", new byte[0]);
                return;
            }
        }
        if (path.equals("/api/session") && post) {
            long now = clock.getAsLong();
            String made = now + ":" + signature(pin.trim(), now);
            used.put(made, now);
            json(out, "{\"session\":\"" + made + "\",\"until\":" + (now + SESSION_MS) + ",\"user\":\"" + shown(who.name)
                    + "\",\"settings\":" + who.settings + "}");
        } else if (path.equals("/api/session/end") && post) {
            used.put(session, 0L);
            json(out, "{}");
        } else if (path.equals("/api/config")) {
            if (post) host.saveConfig(body);
            json(out, post ? "{}" : host.config());
        } else if (path.equals("/api/state")) {
            json(out, "{\"user\":\"" + shown(who.name) + "\",\"settings\":" + who.settings + ",\"tablet\":" + host.state() + "}");
        } else if (path.equals("/api/media")) {
            json(out, host.media());
        } else if (path.equals("/api/folders")) {
            json(out, host.slideFolders());
        } else if (path.equals("/api/printer")) {
            json(out, host.printer());
        } else if (path.equals("/api/printer/camera")) {
            byte[] picture = host.printerPicture();
            if (picture == null) send(out, 404, "text/plain", new byte[0]);
            else send(out, 200, "image/jpeg", picture);
        } else if (path.equals("/api/events")) {
            json(out, host.alarms());
        } else if (path.equals("/api/shot")) {   // ?size=thumb or full&file=...: one picture of an alarm
            String size = value(query, "size"), file = decoded(value(query, "file"));
            byte[] picture = size.matches("thumb|full") && file.matches(SHOT_FILE) ? host.shot(file, size.equals("thumb")) : null;
            if (picture == null) send(out, 404, "text/plain", new byte[0]);
            else send(out, 200, "image/jpeg", picture);
        } else if (path.equals("/api/clip/get")) {   // ?file=...: fetch that video from its camera; asked until it is done
            String file = decoded(value(query, "file"));
            json(out, file.matches(CLIP_FILE) ? host.clipState(file) : "{\"failed\":\"No such video.\"}");
        } else if (path.equals("/api/clip")) {       // ?file=...: the video itself, once it is on the tablet
            String file = decoded(value(query, "file"));
            File video = file.matches(CLIP_FILE) ? host.clip(file) : null;
            if (video == null) send(out, 404, "text/plain", new byte[0]);
            else send(out, "video/mp4", video);
        } else if (path.equals("/api/command") && post) {
            host.command(body);
            json(out, "{}");
        } else if (path.startsWith("/api/")) {
            send(out, 404, "text/plain", new byte[0]);
        } else {
            boolean settings = path.equals("/settings");
            String file = path.equals("/") ? "remote.html" : settings ? "index.html" : path.substring(1);
            byte[] content = file.contains("/") ? null : asset(file);
            if (content == null) {
                send(out, 404, "text/plain", new byte[0]);
                return;
            }
            if (settings) {   // tells the kiosk page it is the remote control; there it may be zoomed
                content = new String(content, StandardCharsets.UTF_8)
                        .replace("<body>", "<body class=\"remote\">").replace(", user-scalable=no", "")
                        .getBytes(StandardCharsets.UTF_8);
            }
            send(out, 200, Mime.of(file), content);
        }
    }

    /**
     * Is `from` on a network that may use the remote control? `at` is the
     * tablet's own address on that connection, whose /24 is "the home network".
     */
    static boolean allowed(InetAddress from, InetAddress at, Access access) {
        byte[] visitor = from.getAddress(), own = at.getAddress();
        if (visitor.length != 4 || own.length != 4) return false;   // IPv4 only
        if (access.lan && visitor[0] == own[0] && visitor[1] == own[1] && visitor[2] == own[2]) return true;
        for (String network : access.also.split("[,;\\s]+")) {
            if (inside(visitor, network)) return true;
        }
        return false;
    }

    /** Is the address inside "10.8.0.0/24" (or equal to a plain "10.8.0.7")? Anything malformed holds nobody. */
    static boolean inside(byte[] address, String network) {
        String[] parts = network.trim().split("/");
        if (parts.length > 2 || !parts[0].matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        int bits = 32;
        if (parts.length == 2) {
            if (!parts[1].matches("\\d{1,2}")) return false;
            bits = Integer.parseInt(parts[1]);
        }
        if (bits < 8 || bits > 32) return false;   // nothing as wide as "everybody"
        String[] numbers = parts[0].split("\\.");
        int base = 0, visitor = 0;
        for (int i = 0; i < 4; i++) {
            int number = Integer.parseInt(numbers[i]);
            if (number > 255) return false;
            base = base << 8 | number;
            visitor = visitor << 8 | (address[i] & 0xff);
        }
        int mask = bits == 32 ? -1 : ~(-1 >>> bits);
        return (base & mask) == (visitor & mask);
    }

    /** 0 when the PIN is a user's; otherwise the HTTP status to answer with. */
    private synchronized int refusal(Access access, String pin) {
        if (access.users.isEmpty()) return 403;                 // nobody is listed: the remote control is off
        long now = clock.getAsLong();
        if (now < lockedUntil) return 429;
        if (pin == null || pin.trim().isEmpty()) return 401;    // a first visit, not a guess
        if (access.users.containsKey(pin.trim())) {
            wrongPins = 0;
            return 0;
        }
        if (++wrongPins >= MAX_TRIES) {
            wrongPins = 0;
            lockedUntil = now + LOCK_MS;
        }
        return 401;
    }

    /** A name as it may go into JSON: without what would end the string. */
    private static String shown(String name) {
        return name.replace("\\", "").replace("\"", "");
    }

    /**
     * The user a session belongs to, when it is good: signed by this tablet
     * for that user's PIN, not too old, used of late, not ended; else null. A
     * session is "<made, ms>:<signature>"; the signature covers the time and
     * the PIN, so a new PIN, or the user taken off the list, ends it.
     */
    private User member(Access access, String session) {
        String[] parts = session.split(":");
        if (parts.length != 2 || !parts[0].matches("\\d{1,15}")) return null;
        long made = Long.parseLong(parts[0]), now = clock.getAsLong();
        if (now < made || now - made > SESSION_MS) return null;
        User found = null;
        for (Map.Entry<String, User> user : access.users.entrySet()) {
            String wanted = signature(user.getKey(), made);
            if (MessageDigest.isEqual(wanted.getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) found = user.getValue();
        }
        if (found == null) return null;
        Long last = used.get(session);
        if (last != null && (last == 0 || now - last > UNUSED_MS)) return null;
        used.put(session, now);
        if (used.size() > 500) used.values().removeIf((when) -> now - when > SESSION_MS);   // the long gone
        return found;
    }

    /** HMAC-SHA256 of time and PIN with the tablet's secret, in hex. */
    private String signature(String pin, long made) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(host.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal((made + "\n" + pin).getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private byte[] asset(String file) {
        try (InputStream in = host.asset(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) bytes.write(chunk, 0, n);
            return bytes.toByteArray();
        } catch (IOException missing) {
            return null;
        }
    }

    /** The value of one "name=value" in a query string, or "". */
    private static String value(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) return pair.substring(name.length() + 1);
        }
        return "";
    }

    /** Header values travel percent-encoded, so a name may be in any alphabet. */
    private static String decoded(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException odd) {
            return text;
        }
    }

    private static void json(OutputStream out, String content) throws IOException {
        send(out, 200, "application/json", content.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int status, String type, byte[] content) throws IOException {
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Refused") + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + content.length + "\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(content);
        out.flush();
    }

    /** A whole file, straight from the disk: a video is tens of megabytes. */
    private static void send(OutputStream out, String type, File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            String head = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + file.length() + "\r\n"
                    + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[65536];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) out.write(chunk, 0, n);
            out.flush();
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int c = in.read(); c >= 0; c = in.read()) {
            if (c == '\n') return text.toString().trim();
            text.append((char) c);
        }
        return text.length() > 0 ? text.toString().trim() : null;
    }
}
