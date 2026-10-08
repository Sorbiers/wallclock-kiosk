package home.wallclock;

import android.util.Log;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amcrest/Dahua camera access the page cannot do itself: the live alarm stream
 * and snapshots, both behind a login. Read-only; never changes camera settings.
 *
 * Alarms are passed to the page as kioskNative.event({cam, code, action, index})
 * and reachability as kioskNative.status({cam, online}); "cam" is the camera's
 * position in the settings.
 */
final class Cameras {
    interface Page {
        void run(String javascript);
    }

    private static final String TAG = "WallClock";
    private static final int HEARTBEAT_S = 5;          // keep-alives we ask the camera to send
    private static final int QUIET_LIMIT_MS = 20000;   // no keep-alive for this long: the stream is dead
    private static final int SNAPSHOT_TIMEOUT_MS = 20000;
    private static final int RETRY_MS = 10000;
    // One event per line: "Code=CrossLineDetection;action=Start;index=0;data={...".
    private static final Pattern EVENT = Pattern.compile("Code=(\\w+);action=(\\w+);index=(\\d+)");
    private static final Map<String, String> NO_STORE = Collections.singletonMap("Cache-Control", "no-store");

    private final Page page;
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Camera> cameras = Collections.emptyList();

    Cameras(Page page) {
        this.page = page;
    }

    /** Take the page's settings (JSON) and listen to those cameras from now on. */
    synchronized void configure(String json) {
        stop();
        List<Camera> found = new ArrayList<>();
        String codes;
        try {
            JSONObject settings = new JSONObject(json);
            String user = settings.optString("camera_user");
            String password = settings.optString("camera_password");
            codes = settings.getJSONObject("alarm").getJSONArray("codes").join(",").replace("\"", "");
            JSONArray list = settings.getJSONArray("cameras");
            for (int i = 0; i < list.length(); i++) {
                found.add(new Camera(list.getJSONObject(i).optString("host").trim(), new Digest(user, password)));
            }
        } catch (JSONException unreadable) {
            Log.w(TAG, "Settings not understood: " + unreadable.getMessage());
            return;
        }
        cameras = found;
        for (int i = 0; i < found.size(); i++) {
            if (found.get(i).host.isEmpty()) continue;
            Listener listener = new Listener(i, found.get(i), codes);
            listeners.add(listener);
            listener.start();
        }
    }

    synchronized void stop() {
        for (Listener listener : listeners) listener.close();
        listeners.clear();
    }

    /** Answer the page's "/cam/&lt;position&gt;/snapshot.jpg" with a fresh still from that camera. */
    WebResourceResponse snapshot(String path) {
        try {
            Camera camera = cameras.get(Integer.parseInt(path.split("/")[2]));
            HttpURLConnection reply = camera.login.get(
                    "http://" + camera.host + "/cgi-bin/snapshot.cgi?channel=1", SNAPSHOT_TIMEOUT_MS);
            if (reply.getResponseCode() == HttpURLConnection.HTTP_OK) {
                return new WebResourceResponse("image/jpeg", null, 200, "OK", NO_STORE, reply.getInputStream());
            }
            Log.w(TAG, "Snapshot refused: HTTP " + reply.getResponseCode());
            reply.disconnect();
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "Snapshot failed: " + failed);
        }
        return PageClient.failure(502, "Bad Gateway");
    }

    private static final class Camera {
        final String host;
        final Digest login;

        Camera(String host, Digest login) {
            this.host = host;
            this.login = login;
        }
    }

    /** Follows one camera's event stream for as long as the settings stand. */
    private final class Listener extends Thread {
        private final int position;
        private final Camera camera;
        private final String address;
        private volatile boolean closed;
        private volatile HttpURLConnection stream;
        private Boolean online;   // last state told to the page; null = none yet

        Listener(int position, Camera camera, String codes) {
            super("camera-" + position);
            this.position = position;
            this.camera = camera;
            this.address = "http://" + camera.host + "/cgi-bin/eventManager.cgi?action=attach&codes=["
                    + codes + "]&heartbeat=" + HEARTBEAT_S;
        }

        void close() {
            closed = true;
            interrupt();
            HttpURLConnection open = stream;
            if (open != null) open.disconnect();
        }

        @Override
        public void run() {
            while (!closed) {
                boolean attached = false;
                try {
                    stream = camera.login.get(address, QUIET_LIMIT_MS);
                    if (stream.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        attached = true;
                        report(true);
                        follow(new BufferedReader(new InputStreamReader(stream.getInputStream(), StandardCharsets.ISO_8859_1)));
                    } else {
                        Log.w(TAG, getName() + ": alarm stream refused, HTTP " + stream.getResponseCode());
                    }
                } catch (IOException | RuntimeException dropped) {
                    if (!closed) Log.w(TAG, getName() + ": alarm stream dropped: " + dropped);
                } finally {
                    HttpURLConnection open = stream;
                    if (open != null) open.disconnect();
                }
                if (closed) return;
                // A working stream that went quiet (the cameras do this now and then):
                // re-attach at once so the blind window stays short.
                if (!attached) report(false);
                try {
                    Thread.sleep(attached ? 1000 : RETRY_MS);
                } catch (InterruptedException stopped) {
                    return;
                }
            }
        }

        private void follow(BufferedReader lines) throws IOException {
            String line;
            while (!closed && (line = lines.readLine()) != null) {
                Matcher event = EVENT.matcher(line);
                if (!event.find()) continue;
                Log.i(TAG, getName() + ": " + event.group(1) + " " + event.group(2));
                page.run("window.kioskNative && kioskNative.event({cam:" + position
                        + ",code:" + JSONObject.quote(event.group(1))
                        + ",action:" + JSONObject.quote(event.group(2))
                        + ",index:" + event.group(3) + "})");
            }
        }

        private void report(boolean nowOnline) {
            if (online != null && online == nowOnline) return;
            online = nowOnline;
            Log.i(TAG, getName() + (nowOnline ? ": online" : ": OFFLINE"));
            page.run("window.kioskNative && kioskNative.status({cam:" + position + ",online:" + nowOnline + "})");
        }
    }
}
