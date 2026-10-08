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

/**
 * Remote control: a tiny web server on the tablet. Opening http://tablet:8080
 * from a PC or phone on the home network shows the same page in "remote" form
 * (mode buttons and the settings), which talks back through /api/...
 *
 * There is no login: anyone on the Wi-Fi can use it. The camera password is
 * never sent out (see MainActivity's Host).
 */
final class Remote extends Thread {
    /** What the server needs from the app. */
    interface Host {
        String config();

        void saveConfig(String json);

        void command(String json);
    }

    static final int PORT = 8080;
    private static final String TAG = "WallClock";
    private static final int MAX_BODY = 200000;

    private final AssetManager assets;
    private final Host host;
    private volatile ServerSocket server;

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
            for (String header = line(in); header != null && !header.isEmpty(); header = line(in)) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    length = Integer.parseInt(header.substring(15).trim());
                }
            }
            byte[] body = new byte[Math.min(length, MAX_BODY)];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            String path = parts.length > 1 ? parts[1].split("\\?")[0] : "/";
            answer(socket.getOutputStream(), parts[0], path, new String(body, 0, got, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "Remote request failed: " + failed);
        }
    }

    private void answer(OutputStream out, String method, String path, String body) throws IOException {
        boolean post = method.equals("POST");
        if (path.equals("/api/config")) {
            if (post) host.saveConfig(body);
            send(out, 200, "application/json", (post ? "{}" : host.config()).getBytes(StandardCharsets.UTF_8));
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
            if (file.equals("index.html")) {   // tells the page it is the remote control
                content = new String(content, StandardCharsets.UTF_8)
                        .replace("<body>", "<body class=\"remote\">").getBytes(StandardCharsets.UTF_8);
            }
            send(out, 200, PageClient.mimeType(file), content);
        }
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
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\n"
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
