package home.wallclock;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Remote control: a tiny web server on the tablet. Opening http://tablet:8080
 * from a PC or phone on the home network shows the same page in "remote" form
 * (mode buttons and the settings), which talks back through /api/...
 *
 * Everything under /api needs the PIN from the settings (header X-Pin); with
 * no PIN set the remote control is off. A few wrong PINs lock it for a
 * minute. Neither the PIN nor the camera password is ever sent out.
 */
final class Remote extends Thread {
    /** What the server needs from the app. */
    interface Host {
        /** The PIN that opens the remote control; empty = it is switched off. */
        String pin();

        String config();

        /** The names of the files the alarm clock can play, as a JSON list. */
        String media();

        void saveConfig(String json);

        void command(String json);
    }

    static final int PORT = 8080;
    private static final String TAG = "WallClock";
    private static final int MAX_BODY = 200000;
    private static final int MAX_TRIES = 5;
    private static final long LOCK_MS = 60000;

    private final AssetManager assets;
    private final Host host;
    private volatile ServerSocket server;
    private int wrongPins;
    private long lockedUntil;

    Remote(AssetManager assets, Host host) {
        super("remote");
        this.assets = assets;
        this.host = host;
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
            server = new ServerSocket(PORT);
        } catch (IOException busy) {
            Log.w(TAG, "Remote control not available: " + busy);
            return;
        }
        while (!server.isClosed()) {
            try {
                final Socket visitor = server.accept();
                new Thread(() -> serve(visitor)).start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket visitor) {
        try (Socket socket = visitor) {
            socket.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String request = line(in);   // "GET /path HTTP/1.1"
            if (request == null) return;
            String[] parts = request.split(" ");
            int length = 0;
            String pin = null;
            for (String header = line(in); header != null && !header.isEmpty(); header = line(in)) {
                String lower = header.toLowerCase();
                if (lower.startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
                if (lower.startsWith("x-pin:")) pin = header.substring(6).trim();
            }
            byte[] body = new byte[Math.min(length, MAX_BODY)];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            String path = parts.length > 1 ? parts[1].split("\\?")[0] : "/";
            answer(socket.getOutputStream(), parts[0], path, pin, new String(body, 0, got, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "Remote request failed: " + failed);
        }
    }

    private void answer(OutputStream out, String method, String path, String pin, String body) throws IOException {
        boolean post = method.equals("POST");
        if (path.startsWith("/api/")) {
            int refusal = refusal(pin);
            if (refusal != 0) {
                send(out, refusal, "text/plain", new byte[0]);
                return;
            }
        }
        if (path.equals("/api/config")) {
            if (post) host.saveConfig(body);
            send(out, 200, "application/json", (post ? "{}" : host.config()).getBytes(StandardCharsets.UTF_8));
        } else if (path.equals("/api/media")) {
            send(out, 200, "application/json", host.media().getBytes(StandardCharsets.UTF_8));
        } else if (path.equals("/api/command") && post) {
            host.command(body);
            send(out, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        } else {
            String file = path.equals("/") ? "index.html" : path.substring(1);
            byte[] content = file.contains("/") ? null : asset(file);
            if (content == null) {
                send(out, 404, "text/plain", new byte[0]);
                return;
            }
            if (file.equals("index.html")) {   // tells the page it is the remote control; there it may be zoomed
                content = new String(content, StandardCharsets.UTF_8)
                        .replace("<body>", "<body class=\"remote\">").replace(", user-scalable=no", "")
                        .getBytes(StandardCharsets.UTF_8);
            }
            send(out, 200, PageClient.mimeType(file), content);
        }
    }

    /** 0 when the PIN is right; otherwise the HTTP status to answer with. */
    private synchronized int refusal(String given) {
        String wanted = host.pin();
        if (wanted.isEmpty()) return 403;                          // no PIN set: remote control is off
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return 429;
        if (given == null || given.isEmpty()) return 401;         // a first visit, not a guess
        if (MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), wanted.getBytes(StandardCharsets.UTF_8))) {
            wrongPins = 0;
            return 0;
        }
        if (++wrongPins >= MAX_TRIES) {
            wrongPins = 0;
            lockedUntil = now + LOCK_MS;
        }
        return 401;
    }

    private byte[] asset(String file) {
        try (InputStream in = assets.open(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) bytes.write(chunk, 0, n);
            return bytes.toByteArray();
        } catch (IOException missing) {
            return null;
        }
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
