package home.wallclock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amcrest/Dahua camera access the page cannot do itself, all behind a login:
 * the live alarm stream, fresh snapshots, and the pictures the cameras take
 * themselves at an alarm and keep on their SD cards (see {@link Card}).
 * Read-only; never changes camera settings.
 *
 * Those pictures are copied to the tablet and kept for a day: every couple of
 * minutes, and all through an alarm. The alarm list and its pictures are
 * served from these copies, so they show at once and without the cameras.
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
    private static final Map<String, String> KEEP = Collections.singletonMap("Cache-Control", "private, max-age=86400");
    private static final long ALARM_GAP_MS = 60000;      // pictures further apart are different alarms
    private static final long KEPT_MS = 24 * 3600000L;   // how long the copies stay on the tablet
    private static final long COPY_EVERY_MS = 120000;    // how often the cameras are asked for what is new
    private static final long COPY_SOON_MS = 20000;      // ...or this soon, when the list is being looked at
    private static final int THUMB_WIDTH = 480;
    // A copy's name tells all about it: "<taken, ms>_<camera>_<rules, joined by +>_<its place on the card, hashed>.jpg".
    private static final Pattern KEPT = Pattern.compile("(\\d+)_(\\d+)_([\\w+]*)_[0-9A-F]+\\.jpg");

    private final Page page;
    private final File store;                 // the copies in full; small ones for lists in "small" inside it
    private final Object copying = new Object();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Camera> cameras = Collections.emptyList();
    private volatile Set<String> codes = Collections.emptySet();
    private volatile Set<Integer> silent = Collections.emptySet();   // cameras that did not answer the last time
    private volatile long copiedAt;
    private Keeper keeper;

    Cameras(Page page, File store) {
        this.page = page;
        this.store = store;
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
                JSONObject camera = list.getJSONObject(i);
                found.add(new Camera(camera.optString("name", "Camera " + (i + 1)), camera.optString("host").trim(), user, password));
            }
        } catch (JSONException unreadable) {
            Log.w(TAG, "Settings not understood: " + unreadable.getMessage());
            return;
        }
        cameras = found;
        this.codes = new HashSet<>(java.util.Arrays.asList(codes.split(",")));
        keeper = new Keeper();
        keeper.start();
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
        if (keeper != null) keeper.close();
        keeper = null;
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

    /** The page's "/cam/..." requests: the alarm list, one of its pictures, what an alarm has brought, or a fresh snapshot. */
    WebResourceResponse answer(Uri url) {
        String path = url.getPath() == null ? "" : url.getPath();
        try {
            if (path.equals("/cam/alarms")) return json(alarms());
            if (path.equals("/cam/shot")) {
                byte[] jpeg = picture(url.getQueryParameter("file"), !"full".equals(url.getQueryParameter("size")));
                if (jpeg == null) return PageClient.failure(404, "Not Found");
                return new WebResourceResponse("image/jpeg", null, 200, "OK", KEEP, new ByteArrayInputStream(jpeg));
            }
            String[] parts = path.split("/");
            if (parts.length == 4 && parts[3].equals("since")) {
                return json(since(Integer.parseInt(parts[2]), Long.parseLong(url.getQueryParameter("ms"))));
            }
        } catch (RuntimeException odd) {
            return PageClient.failure(400, "Bad Request");
        }
        return snapshot(path);
    }

    private static WebResourceResponse json(String text) {
        return new WebResourceResponse("application/json", "utf-8", 200, "OK", NO_STORE,
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The alarms of the last day, from the tablet's copies of the cameras' own
     * pictures: pictures of one camera less than a minute apart are one alarm.
     * JSON: {"alarms": [{cam, name, at, codes, shots: [{at, file}]}], "failed":
     * [names of the cameras that did not answer when last asked]}, the newest
     * first, times in milliseconds. Asking also sends for what is new, soon.
     */
    String alarms() {
        long now = System.currentTimeMillis();
        Keeper sender = keeper;
        if (sender != null && now - copiedAt > COPY_SOON_MS) sender.hurry();
        List<Camera> known = cameras;
        List<String> names = kept(-1, now - KEPT_MS);
        List<JSONObject> all = new ArrayList<>();
        try {
            for (int position = 0; position < known.size(); position++) {
                JSONObject alarm = null;
                long last = 0;
                for (String name : names) {
                    Matcher told = KEPT.matcher(name);
                    if (!told.matches() || Integer.parseInt(told.group(2)) != position) continue;
                    long at = Long.parseLong(told.group(1));
                    if (alarm == null || at - last > ALARM_GAP_MS) {
                        alarm = new JSONObject().put("cam", position).put("name", known.get(position).name).put("at", at)
                                .put("codes", new JSONArray()).put("shots", new JSONArray());
                        all.add(alarm);
                    }
                    JSONArray fired = alarm.getJSONArray("codes");
                    for (String code : told.group(3).split("\\+")) {
                        if (!code.isEmpty() && !fired.toString().contains("\"" + code + "\"")) fired.put(code);
                    }
                    alarm.getJSONArray("shots").put(new JSONObject().put("at", at).put("file", name));
                    last = at;
                }
            }
            Collections.sort(all, (a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
            JSONArray failed = new JSONArray();
            for (int position : silent) {
                if (position < known.size()) failed.put(known.get(position).name);
            }
            return new JSONObject().put("alarms", new JSONArray(all)).put("failed", failed).toString();
        } catch (JSONException impossible) {
            return "{\"alarms\":[],\"failed\":[]}";
        }
    }

    /**
     * During an alarm: what one camera has taken since that moment, copied over
     * first, oldest first, as JSON [{at, file}].
     */
    String since(int position, long moment) {
        copy(position, moment);
        JSONArray found = new JSONArray();
        try {
            for (String name : kept(position, moment)) {
                Matcher told = KEPT.matcher(name);
                if (told.matches()) found.put(new JSONObject().put("at", Long.parseLong(told.group(1))).put("file", name));
            }
        } catch (JSONException impossible) {
            // an empty list
        }
        return found.toString();
    }

    /** One of the copies, as JPEG bytes, in full or small; null when there is none of that name. */
    byte[] picture(String file, boolean small) {
        if (file == null || !KEPT.matcher(file).matches()) return null;
        File full = new File(store, file), little = new File(new File(store, "small"), file);
        try {
            if (small && little.isFile()) return read(new FileInputStream(little));
            return full.isFile() ? read(new FileInputStream(full)) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The names of the copies taken since then, oldest first; of one camera, or of all (-1). */
    private List<String> kept(int position, long since) {
        List<String> names = new ArrayList<>();
        String[] all = store.list();
        for (int i = 0; all != null && i < all.length; i++) {
            Matcher told = KEPT.matcher(all[i]);
            if (!told.matches() || Long.parseLong(told.group(1)) < since) continue;
            if (position < 0 || Integer.parseInt(told.group(2)) == position) names.add(all[i]);
        }
        Collections.sort(names, (a, b) -> Long.compare(Long.parseLong(a.substring(0, a.indexOf('_'))),
                Long.parseLong(b.substring(0, b.indexOf('_')))));
        return names;
    }

    /**
     * Copies to the tablet what one camera has recorded since then and is not
     * here yet, the newest first: each picture in full (about a megabyte), and
     * a small one for lists. False when the camera could not be asked.
     */
    private boolean copy(int position, long since) {
        List<Camera> asked = cameras;
        if (position < 0 || position >= asked.size() || asked.get(position).host.isEmpty()) return true;
        Camera camera = asked.get(position);
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);   // the camera's own clock
        synchronized (copying) {
            try {
                List<Card.Shot> shots = Card.find(camera.host, camera.user, camera.password,
                        stamp.format(new Date(since)), stamp.format(new Date(System.currentTimeMillis() + 600000)), codes);
                Collections.sort(shots, (a, b) -> b.time.compareTo(a.time));
                File small = new File(store, "small");
                small.mkdirs();
                for (Card.Shot shot : shots) {
                    String name = stamp.parse(shot.time).getTime() + "_" + position + "_" + android.text.TextUtils.join("+", shot.codes)
                            + "_" + Card.md5(camera.host + shot.path).substring(0, 12) + ".jpg";
                    File full = new File(store, name);
                    if (full.isFile() || !KEPT.matcher(name).matches()) continue;
                    byte[] jpeg = load(camera, shot.path);
                    if (jpeg == null) continue;
                    write(new File(small, name), shrunk(jpeg));
                    write(full, jpeg);
                }
                return true;
            } catch (IOException | JSONException | ParseException | RuntimeException unanswered) {
                Log.w(TAG, "camera-" + position + ": its card could not be read: " + unanswered);
                return false;
            }
        }
    }

    /** One picture from a camera's card, by its place there; null when the camera does not give it. */
    private static byte[] load(Camera camera, String path) throws IOException {
        StringBuilder address = new StringBuilder("http://" + camera.host + "/cgi-bin/RPC_Loadfile");
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (c < 128 && (Character.isLetterOrDigit(c) || "/_.-~".indexOf(c) >= 0)) address.append(c);
            else address.append(String.format(Locale.ROOT, "%%%02X", b & 0xff));
        }
        HttpURLConnection reply = camera.login.get(address.toString(), SNAPSHOT_TIMEOUT_MS);
        try {
            if (reply.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Stored picture refused: HTTP " + reply.getResponseCode());
                return null;
            }
            byte[] jpeg = read(reply.getInputStream());
            return jpeg.length > 3 && (jpeg[0] & 0xff) == 0xff && (jpeg[1] & 0xff) == 0xd8 ? jpeg : null;
        } finally {
            reply.disconnect();
        }
    }

    /** A small copy of a JPEG, for lists. */
    private static byte[] shrunk(byte[] jpeg) {
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, size);
        BitmapFactory.Options shrink = new BitmapFactory.Options();
        shrink.inSampleSize = 1;
        while (size.outWidth / (shrink.inSampleSize * 2) >= THUMB_WIDTH) shrink.inSampleSize *= 2;
        Bitmap little = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, shrink);
        if (little == null) return jpeg;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        little.compress(Bitmap.CompressFormat.JPEG, 80, out);
        little.recycle();
        return out.toByteArray();
    }

    /** Written under another name first: a copy is either whole or not there. */
    private static void write(File file, byte[] content) throws IOException {
        File part = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(content);
        }
        if (!part.renameTo(file)) throw new IOException("Could not keep " + file.getName());
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream source = in) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n = source.read(chunk); n > 0; n = source.read(chunk)) bytes.write(chunk, 0, n);
            return bytes.toByteArray();
        }
    }

    /**
     * Keeps the copies up to date: every couple of minutes it fetches what the
     * cameras have recorded in the last day and is not here yet, and throws
     * away what is older than that.
     */
    private final class Keeper extends Thread {
        private volatile boolean closed;

        Keeper() {
            super("camera-copies");
        }

        synchronized void hurry() {
            notify();
        }

        void close() {
            closed = true;
            interrupt();
        }

        @Override
        public void run() {
            while (!closed) {
                long now = System.currentTimeMillis();
                Set<Integer> unanswered = new HashSet<>();
                for (int position = 0; position < cameras.size() && !closed; position++) {
                    if (!copy(position, now - KEPT_MS)) unanswered.add(position);
                }
                if (closed) return;
                silent = unanswered;
                copiedAt = System.currentTimeMillis();
                for (File folder : new File[] {store, new File(store, "small")}) {
                    String[] names = folder.list();
                    for (int i = 0; names != null && i < names.length; i++) {
                        Matcher told = KEPT.matcher(names[i]);
                        boolean old = told.matches() ? Long.parseLong(told.group(1)) < now - KEPT_MS : names[i].endsWith(".part");
                        if (old) new File(folder, names[i]).delete();
                    }
                }
                synchronized (this) {
                    try {
                        wait(COPY_EVERY_MS);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                }
            }
        }
    }

    private static final class Camera {
        final String name;
        final String host;
        final String user;
        final String password;
        final Digest login;

        Camera(String name, String host, String user, String password) {
            this.name = name;
            this.host = host;
            this.user = user;
            this.password = password;
            this.login = new Digest(user, password);
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
