package home.wallclock;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A Bambu Lab printer (A1, P1) on the home network: what it is doing, and
 * pictures from its camera. Read only: nothing on the printer is changed.
 *
 * Its status comes by MQTT over TLS (port 8883), signed in as "bblp" with the
 * printer's LAN access code; the printer's serial number, needed for the
 * topics, is the name in its certificate. The printer sends what has changed;
 * the app keeps the whole and asks for all of it now and then ("pushall").
 * The page is told of every report as kioskNative.printer({...}).
 *
 * The camera (TLS, port 6000) sends JPEG pictures one after another, a few
 * seconds apart, once told the same access code. It is read only while
 * someone looks at its pictures.
 */
final class Printer {
    interface Page {
        void run(String javascript);
    }

    private static final String TAG = "WallClock";
    private static final int RETRY_MS = 15000;
    private static final long PING_MS = 30000;          // the connection is kept alive with a ping this often
    private static final long FULL_EVERY_MS = 300000;   // a full report is asked for this often
    private static final long CAMERA_IDLE_MS = 20000;   // the camera is let go when no picture was asked for in this long
    private static final long PICTURE_OLD_MS = 15000;   // a picture this old is not handed out
    // What the page is told of; the printer's report has much more.
    private static final String[] SHOWN = {"gcode_state", "mc_percent", "mc_remaining_time", "layer_num", "total_layer_num",
            "subtask_name", "nozzle_temper", "nozzle_target_temper", "bed_temper", "bed_target_temper", "spd_lvl",
            "print_error", "hms", "wifi_signal", "vt_tray", "ams"};

    private final Page page;
    private volatile String host = "";
    private volatile String code = "";
    private Listener listener;
    private final JSONObject status = new JSONObject();   // the printer's whole report, as last told; guarded by itself
    private volatile boolean online;
    private volatile long reportedAt;
    private final Object camera = new Object();
    private byte[] latest;            // the camera's last picture, and when it came; guarded by `camera`
    private long latestAt;
    private long askedAt;
    private Thread watcher;

    Printer(Page page) {
        this.page = page;
    }

    /** Take the page's settings (JSON): the printer's address and access code. */
    synchronized void configure(String json) {
        String wantedHost = "", wantedCode = "";
        try {
            JSONObject printer = new JSONObject(json).optJSONObject("printer");
            if (printer != null) {
                wantedHost = printer.optString("host").trim();
                wantedCode = printer.optString("code").trim();
            }
        } catch (JSONException unreadable) {
            return;
        }
        if (wantedHost.equals(host) && wantedCode.equals(code) && listener != null) return;
        stop();
        host = wantedHost;
        code = wantedCode;
        synchronized (status) {
            for (String key : names(status)) status.remove(key);
        }
        online = false;
        if (host.isEmpty() || code.isEmpty()) return;
        listener = new Listener();
        listener.start();
    }

    synchronized void stop() {
        if (listener != null) listener.close();
        listener = null;
    }

    /** What the printer is doing, as JSON: {online, at, and the report's fields the page uses}. */
    String state() {
        try {
            JSONObject shown = new JSONObject().put("online", online).put("at", reportedAt).put("configured", !host.isEmpty());
            synchronized (status) {
                for (String key : SHOWN) {
                    if (status.has(key)) shown.put(key, status.get(key));
                }
            }
            return shown.toString();
        } catch (JSONException impossible) {
            return "{\"online\":false}";
        }
    }

    /** The camera's latest picture (JPEG), waiting a few seconds for one if need be; null when none comes. */
    byte[] picture() {
        if (host.isEmpty() || code.isEmpty()) return null;
        synchronized (camera) {
            askedAt = System.currentTimeMillis();
            if (watcher == null || !watcher.isAlive()) {
                watcher = new Thread(this::watch, "printer-camera");
                watcher.start();
            }
            long until = askedAt + 6000;
            try {
                while ((latest == null || System.currentTimeMillis() - latestAt > PICTURE_OLD_MS) && System.currentTimeMillis() < until) {
                    camera.wait(500);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return latest != null && System.currentTimeMillis() - latestAt <= PICTURE_OLD_MS ? latest : null;
        }
    }

    /** Reads the camera's pictures while they are wanted. */
    private void watch() {
        String to = host, with = code;
        try (SSLSocket socket = open(to, 6000)) {
            OutputStream out = socket.getOutputStream();
            byte[] hello = new byte[80];   // a fixed head, then "bblp" and the access code, each in 32 bytes
            hello[0] = 0x40;
            hello[5] = 0x30;
            System.arraycopy("bblp".getBytes(StandardCharsets.US_ASCII), 0, hello, 16, 4);
            byte[] secret = with.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(secret, 0, hello, 48, Math.min(32, secret.length));
            out.write(hello);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] head = new byte[16];
            while (true) {
                synchronized (camera) {
                    if (System.currentTimeMillis() - askedAt > CAMERA_IDLE_MS || !to.equals(host)) return;
                }
                in.readFully(head);
                int size = (head[0] & 0xff) | (head[1] & 0xff) << 8 | (head[2] & 0xff) << 16;
                if (size <= 0 || size > 5000000) throw new IOException("not a picture's head");
                byte[] jpeg = new byte[size];
                in.readFully(jpeg);
                synchronized (camera) {
                    latest = jpeg;
                    latestAt = System.currentTimeMillis();
                    camera.notifyAll();
                }
            }
        } catch (IOException | GeneralSecurityException failed) {
            Log.w(TAG, "Printer camera: " + failed);
        }
    }

    /** A TLS connection to the printer. It shows a certificate of Bambu's own making, so any is taken: it is the home network. */
    private static SSLSocket open(String host, int port) throws IOException, GeneralSecurityException {
        TrustManager[] anyone = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, anyone, null);
        SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setSoTimeout(15000);
        socket.startHandshake();
        return socket;
    }

    private static String[] names(JSONObject object) {
        JSONArray names = object.names();
        String[] all = new String[names == null ? 0 : names.length()];
        for (int i = 0; i < all.length; i++) all[i] = names.optString(i);
        return all;
    }

    /** Keeps the status connection up, and takes in the printer's reports. */
    private final class Listener extends Thread {
        private volatile boolean closed;
        private volatile SSLSocket socket;

        Listener() {
            super("printer");
        }

        void close() {
            closed = true;
            interrupt();
            new Thread(() -> {   // closing a socket is not allowed on the main thread
                try {
                    if (socket != null) socket.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }).start();
        }

        @Override
        public void run() {
            while (!closed) {
                try {
                    follow();
                } catch (IOException | GeneralSecurityException | JSONException | RuntimeException lost) {
                    if (!closed) Log.w(TAG, "Printer: " + lost);
                }
                online = false;
                tell();
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException stopped) {
                    return;
                }
            }
        }

        private void follow() throws IOException, GeneralSecurityException, JSONException {
            socket = open(host, 8883);
            try {
                socket.setSoTimeout(5000);
                String name = ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getSubjectX500Principal().getName();
                String serial = name.replaceAll(".*CN=([^,]+).*", "$1");
                OutputStream out = socket.getOutputStream();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                out.write(packet(0x10, concat(text("MQTT"), new byte[] {4, (byte) 0xC2, 0, 60},
                        text("wallclock-" + Long.toHexString(System.nanoTime())), text("bblp"), text(code))));
                byte[] answer = read(in, true);
                if (answer == null || (answer[0] & 0xf0) != 0x20 || answer.length < 3 || answer[2] != 0) {
                    throw new IOException("the printer refused the access code");
                }
                out.write(packet(0x82, concat(new byte[] {0, 1}, text("device/" + serial + "/report"), new byte[] {0})));
                byte[] full = packet(0x30, concat(text("device/" + serial + "/request"),
                        "{\"pushing\":{\"sequence_id\":\"0\",\"command\":\"pushall\"}}".getBytes(StandardCharsets.UTF_8)));
                out.write(full);
                long pinged = System.currentTimeMillis(), asked = pinged;
                while (!closed) {
                    long now = System.currentTimeMillis();
                    if (now - pinged > PING_MS) {
                        out.write(new byte[] {(byte) 0xC0, 0});
                        pinged = now;
                    }
                    if (now - asked > FULL_EVERY_MS) {
                        out.write(full);
                        asked = now;
                    }
                    byte[] got = read(in, false);
                    if (got != null && (got[0] & 0xf0) == 0x30) take(got);
                }
            } finally {
                socket.close();
            }
        }

        /** One MQTT packet, its first byte included; null when nothing came for a while (only `waiting` for it may time out). */
        private byte[] read(DataInputStream in, boolean waiting) throws IOException {
            int first;
            try {
                first = in.read();
            } catch (SocketTimeoutException quiet) {
                if (waiting) throw quiet;
                return null;
            }
            if (first < 0) throw new IOException("the printer closed the connection");
            int size = 0;
            for (int shift = 0; ; shift += 7) {
                int b = in.readUnsignedByte();
                size |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) break;
                if (shift > 21) throw new IOException("a packet too long");
            }
            byte[] whole = new byte[1 + size];
            whole[0] = (byte) first;
            in.readFully(whole, 1, size);
            return whole;
        }

        /** A report: its fields replace those kept. */
        private void take(byte[] publish) throws JSONException {
            int at = 1;   // read() keeps the first byte and the body, not the length between them
            int topic = (publish[at] & 0xff) << 8 | (publish[at + 1] & 0xff);
            at += 2 + topic + (((publish[0] >> 1) & 3) > 0 ? 2 : 0);
            JSONObject report = new JSONObject(new String(publish, at, publish.length - at, StandardCharsets.UTF_8));
            JSONObject print = report.optJSONObject("print");
            if (print == null) return;
            synchronized (status) {
                for (String key : names(print)) status.put(key, print.get(key));
            }
            online = true;
            reportedAt = System.currentTimeMillis();
            tell();
        }

        private void tell() {
            page.run("window.kioskNative && kioskNative.printer && kioskNative.printer(" + state() + ")");
        }
    }

    private static byte[] text(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return concat(new byte[] {(byte) (bytes.length >> 8), (byte) bytes.length}, bytes);
    }

    private static byte[] packet(int kind, byte[] body) {
        byte[] length = new byte[4];
        int n = 0, size = body.length;
        do {
            int b = size % 128;
            size /= 128;
            length[n++] = (byte) (b | (size > 0 ? 0x80 : 0));
        } while (size > 0);
        return concat(new byte[] {(byte) kind}, Arrays.copyOf(length, n), body);
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part.length;
        byte[] all = new byte[size];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }
}
