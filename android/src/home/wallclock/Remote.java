package home.wallclock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remote control: a tiny web server on the tablet. http://tablet:8080 (or the
 * port the settings name) from a PC or phone shows a page made like a TV
 * remote (remote.html): what the wall clock is doing, buttons to switch its
 * page, the camera alarms with the pictures the cameras took.
 * "/settings" is the kiosk page itself in "remote" form, with every setting.
 *
 * Who gets in: only addresses on the networks the settings allow (the tablet's
 * own /24, and others named there, such as a VPN's), and under "/api" only the
 * users listed there, by name and PIN (headers X-User, X-Pin). A few wrong
 * PINs lock it for a minute. No PIN and no camera password is ever sent out.
 *
 * Nothing here needs Android, so these rules are tested on a PC
 * (tools/test_remote.py).
 */
final class Remote extends Thread {
    /** Who may use the remote control. Read from the settings at every request. */
    static final class Access {
        boolean lan = true;                                   // the tablet's own /24
        String also = "";                                     // more networks: "10.8.0.0/24, 192.168.50.7"
        final Map<String, String> users = new HashMap<>();    // name in lower case -> PIN
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

        /** What the tablet is doing right now, as JSON. */
        String state();

        /** The camera alarms of the last day, as JSON (see Cameras.alarms). */
        String alarms();

        /** One of the tablet's copies of the cameras' alarm pictures, small or in full, as JPEG bytes; null when there is none. */
        byte[] shot(String file, boolean small);

        void log(String message);
    }

    static final int DEFAULT_PORT = 8080;   // the settings may name another
    /** How the tablet names its copies of the cameras' alarm pictures (see Cameras). Nothing else is handed out. */
    static final String SHOT_FILE = "\\d+_\\d+_[\\w+]*_[0-9A-F]+\\.jpg";
    private static final int MAX_BODY = 200000;
    private static final int MAX_TRIES = 5;
    private static final long LOCK_MS = 60000;
    private static final int MAX_VISITORS = 24;   // at once; more than a household's browsers need

    private final int port;
    private final Host host;
    private final AtomicInteger visitors = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile boolean busy;
    private int wrongPins;
    private long lockedUntil;

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
            String user = null, pin = null;
            for (String header = line(in); header != null && !header.isEmpty(); header = line(in)) {
                String lower = header.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
                if (lower.startsWith("x-user:")) user = decoded(header.substring(7).trim());
                if (lower.startsWith("x-pin:")) pin = header.substring(6).trim();
            }
            byte[] body = new byte[Math.max(0, Math.min(length, MAX_BODY))];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            String[] target = (parts.length > 1 ? parts[1] : "/").split("\\?", 2);
            answer(out, parts[0], target[0], target.length > 1 ? target[1] : "", access, user, pin,
                    new String(body, 0, got, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failed) {
            host.log("Remote request failed: " + failed);
        }
    }

    private void answer(OutputStream out, String method, String path, String query, Access access,
                        String user, String pin, String body) throws IOException {
        boolean post = method.equals("POST");
        if (path.startsWith("/api/")) {
            int refusal = refusal(access, user, pin);
            if (refusal != 0) {
                send(out, refusal, "text/plain", new byte[0]);
                return;
            }
        }
        if (path.equals("/api/config")) {
            if (post) host.saveConfig(body);
            json(out, post ? "{}" : host.config());
        } else if (path.equals("/api/state")) {
            json(out, "{\"user\":\"" + user.replace("\\", "").replace("\"", "") + "\",\"tablet\":" + host.state() + "}");
        } else if (path.equals("/api/media")) {
            json(out, host.media());
        } else if (path.equals("/api/events")) {
            json(out, host.alarms());
        } else if (path.equals("/api/shot")) {   // ?size=thumb or full&file=...: one picture of an alarm
            String size = value(query, "size"), file = decoded(value(query, "file"));
            byte[] picture = size.matches("thumb|full") && file.matches(SHOT_FILE) ? host.shot(file, size.equals("thumb")) : null;
            if (picture == null) send(out, 404, "text/plain", new byte[0]);
            else send(out, 200, "image/jpeg", picture);
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

    /** 0 when the user and PIN are right; otherwise the HTTP status to answer with. */
    private synchronized int refusal(Access access, String user, String pin) {
        if (access.users.isEmpty()) return 403;                 // nobody is listed: the remote control is off
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return 429;
        if (pin == null || pin.isEmpty()) return 401;           // a first visit, not a guess
        String wanted = user == null ? null : access.users.get(user.trim().toLowerCase(Locale.ROOT));
        if (wanted != null && MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8), wanted.getBytes(StandardCharsets.UTF_8))) {
            wrongPins = 0;
            return 0;
        }
        if (++wrongPins >= MAX_TRIES) {   // a wrong name counts like a wrong PIN: no telling which it was
            wrongPins = 0;
            lockedUntil = now + LOCK_MS;
        }
        return 401;
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

    private static String line(InputStream in) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int c = in.read(); c >= 0; c = in.read()) {
            if (c == '\n') return text.toString().trim();
            text.append((char) c);
        }
        return text.length() > 0 ? text.toString().trim() : null;
    }
}
