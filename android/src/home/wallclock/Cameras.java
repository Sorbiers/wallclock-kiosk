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
import java.util.HashMap;
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
 * With an alarm's own pictures come the camera's other ones (of motion, say)
 * from a few seconds before it to a few seconds after. The camera's video of
 * an alarm is only noted; it is copied when someone asks to watch it, and
 * kept a day as well.
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
    // A copied video's name: "<begins, ms>_<camera>_<lasts, s>_<its place on the card, hashed>.mp4".
    private static final Pattern CLIP = Pattern.compile("(\\d+)_(\\d+)_(\\d+)_[0-9A-F]+\\.mp4");
    private static final long CLIP_NEAR_MS = 5000;       // a recording this close to an alarm is a video of it
    private static final long CLIP_SETTLED_MS = 20000;   // ...and is offered once it has been over for this long
    private static final long FAILURE_TOLD_MS = 15000;   // how long a failed copy waits to be told of

    private final Page page;
    private final File store;                 // the copies in full; small ones for lists in "small" inside it
    private final Object copying = new Object();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Camera> cameras = Collections.emptyList();
    private volatile Set<String> codes = Collections.emptySet();
    private volatile Set<Integer> silent = Collections.emptySet();   // cameras that did not answer the last time
    private volatile long copiedAt;
    private volatile long aroundMs = 10000;   // the camera's other pictures are kept from this long before an alarm to this long after
    private volatile Map<Integer, List<Recording>> recordings = Collections.emptyMap();   // each camera's videos of the last day, as its card lists them
    private final Map<String, Fetch> fetches = new HashMap<>();                           // videos being copied, by the name of the copy
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
        long around;
        try {
            JSONObject settings = new JSONObject(json);
            around = Math.max(0, Math.min(60, settings.getJSONObject("alarm").optInt("around_seconds", 10))) * 1000L;
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
        aroundMs = around;
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

    /**
     * The page's "/cam/..." requests: the alarm list, one of its pictures, a
     * video of an alarm (first asked for, then played, in parts), what an
     * alarm has brought, or a fresh snapshot.
     */
    WebResourceResponse answer(Uri url, String range) {
        String path = url.getPath() == null ? "" : url.getPath();
        try {
            if (path.equals("/cam/alarms")) return json(alarms());
            if (path.equals("/cam/clip/get")) return json(clipState(url.getQueryParameter("file")));
            if (path.equals("/cam/clip")) {
                File video = clip(url.getQueryParameter("file"));
                return video == null ? PageClient.failure(404, "Not Found") : PageClient.part(video, "video/mp4", range);
            }
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
     * JSON: {"alarms": [{cam, name, at, codes, shots: [{at, file}], videos:
     * [{file, at, seconds}]}], "failed": [names of the cameras that did not
     * answer when last asked]}, the newest first, times in milliseconds. The
     * videos are the camera's recordings of that alarm; they are still on its
     * card (see clipState). Asking also sends for what is new, soon.
     */
    String alarms() {
        long now = System.currentTimeMillis();
        Keeper sender = keeper;
        if (sender != null && now - copiedAt > COPY_SOON_MS) sender.hurry();
        List<Camera> known = cameras;
        List<String> names = kept(-1, now - KEPT_MS);
        Map<Integer, List<Recording>> recorded = recordings;
        List<Alarm> found = new ArrayList<>();
        List<JSONObject> all = new ArrayList<>();
        try {
            for (int position = 0; position < known.size(); position++) {
                Alarm alarm = null;
                for (String name : names) {
                    Matcher told = KEPT.matcher(name);
                    if (!told.matches() || Integer.parseInt(told.group(2)) != position) continue;
                    long at = Long.parseLong(told.group(1));
                    boolean rule = !told.group(3).isEmpty();   // an alarm rule took it; if none did, it is from around an alarm
                    // A new alarm begins a minute after the last picture a rule took. A picture from
                    // around an alarm goes with the alarm it is close to: the last one, or one about to begin.
                    long limit = alarm == null || rule || alarm.first == 0 ? ALARM_GAP_MS : aroundMs;
                    if (alarm == null || at - (alarm.first > 0 ? alarm.last : alarm.seen) > limit) {
                        alarm = new Alarm(position, new JSONObject().put("cam", position).put("name", known.get(position).name)
                                .put("at", at).put("codes", new JSONArray()).put("shots", new JSONArray()));
                        found.add(alarm);
                    }
                    JSONArray fired = alarm.json.getJSONArray("codes");
                    for (String code : told.group(3).split("\\+")) {
                        if (!code.isEmpty() && !fired.toString().contains("\"" + code + "\"")) fired.put(code);
                    }
                    alarm.json.getJSONArray("shots").put(new JSONObject().put("at", at).put("file", name));
                    alarm.seen = at;
                    if (rule) {
                        if (alarm.first == 0) alarm.first = at;
                        alarm.last = at;
                    }
                }
            }
            for (Alarm alarm : found) {
                if (alarm.first == 0) continue;   // only pictures from around an alarm whose own are gone: nothing to list
                JSONArray videos = new JSONArray();
                List<Recording> clips = recorded.get(alarm.position);
                for (int i = 0; clips != null && i < clips.size(); i++) {
                    Recording clip = clips.get(i);
                    if (clip.end < alarm.first - CLIP_NEAR_MS || clip.start > alarm.last + CLIP_NEAR_MS) continue;
                    if (clip.end > now - CLIP_SETTLED_MS) continue;   // perhaps still being recorded
                    videos.put(new JSONObject().put("file", clip.name).put("at", clip.start).put("seconds", (clip.end - clip.start) / 1000));
                }
                all.add(alarm.json.put("videos", videos));
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

    /** An alarm of the list in the making: when its rules took their first and last picture, and when its last picture of any kind was taken. */
    private static final class Alarm {
        final int position;
        final JSONObject json;
        long first;
        long last;
        long seen;

        Alarm(int position, JSONObject json) {
            this.position = position;
            this.json = json;
        }
    }

    /** A video on a camera's card: when it runs, where it is, how big, and what its copy on the tablet is called. */
    private static final class Recording {
        final long start;
        final long end;
        final String path;
        final long bytes;
        final String name;

        Recording(long start, long end, String path, long bytes, String name) {
            this.start = start;
            this.end = end;
            this.path = path;
            this.bytes = bytes;
            this.name = name;
        }
    }

    /** A video on its way from a camera to the tablet. */
    private static final class Fetch {
        final long size;
        volatile long got;
        volatile String failed;
        volatile long failedAt;

        Fetch(long size) {
            this.size = size;
        }
    }

    /**
     * Someone wants to watch a video of an alarm: it is copied from the
     * camera's card, unless it is here already. Asked again and again while it
     * comes. JSON: {done: true} when it can be played ("/cam/clip"), {done:
     * false, got, size} on the way, {failed: why} when it did not come.
     */
    String clipState(String file) {
        try {
            Matcher told = file == null ? null : CLIP.matcher(file);
            if (told == null || !told.matches()) return new JSONObject().put("failed", "No such video.").toString();
            final File video = new File(new File(store, "video"), file);
            if (video.isFile()) return new JSONObject().put("done", true).toString();
            synchronized (fetches) {
                Fetch fetch = fetches.get(file);
                if (fetch != null && fetch.failed != null) {
                    fetches.remove(file);
                    if (System.currentTimeMillis() - fetch.failedAt < FAILURE_TOLD_MS) return new JSONObject().put("failed", fetch.failed).toString();
                    fetch = null;   // an old failure nobody waited for: try again
                }
                if (fetch == null) {
                    int position = Integer.parseInt(told.group(2));
                    List<Camera> known = cameras;
                    List<Recording> clips = recordings.get(position);
                    Recording wanted = null;
                    for (int i = 0; clips != null && i < clips.size(); i++) {
                        if (clips.get(i).name.equals(file)) wanted = clips.get(i);
                    }
                    if (wanted == null || position >= known.size()) {
                        return new JSONObject().put("failed", "The camera no longer lists this video.").toString();
                    }
                    final Fetch begun = fetch = new Fetch(wanted.bytes);
                    final Camera camera = known.get(position);
                    final Recording from = wanted;
                    fetches.put(file, fetch);
                    new Thread(() -> fetch(camera, from, video, begun), "camera-video").start();
                }
                return new JSONObject().put("done", false).put("got", fetch.got).put("size", fetch.size).toString();
            }
        } catch (JSONException impossible) {
            return "{\"failed\":\"\"}";
        }
    }

    /** The copy of a video, when it is here whole; else null. */
    File clip(String file) {
        if (file == null || !CLIP.matcher(file).matches()) return null;
        File video = new File(new File(store, "video"), file);
        return video.isFile() ? video : null;
    }

    /** Copies one video from a camera's card to the tablet: tens of megabytes, so straight into a file. */
    private void fetch(Camera camera, Recording wanted, File video, Fetch fetch) {
        File part = new File(video.getPath() + ".part");
        try {
            video.getParentFile().mkdirs();
            HttpURLConnection reply = camera.login.get(onCard(camera, wanted.path), SNAPSHOT_TIMEOUT_MS);
            try {
                if (reply.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + reply.getResponseCode());
                try (InputStream in = reply.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] chunk = new byte[65536];
                    for (int n = in.read(chunk); n > 0; n = in.read(chunk)) {
                        out.write(chunk, 0, n);
                        fetch.got += n;
                    }
                }
            } finally {
                reply.disconnect();
            }
            if (part.length() < wanted.bytes) throw new IOException("only " + part.length() + " of " + wanted.bytes + " bytes came");
            if (!part.renameTo(video)) throw new IOException("the copy could not be kept");
            synchronized (fetches) {
                fetches.remove(video.getName());
            }
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "A video could not be copied from its camera: " + failed);
            part.delete();
            fetch.failedAt = System.currentTimeMillis();
            fetch.failed = "The video could not be copied from the camera.";
        }
    }

    /**
     * During an alarm: what one camera has taken since that moment, copied over
     * first, oldest first, as JSON [{at, file}].
     */
    String since(int position, long moment) {
        copy(position, moment, false);
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
     * a small one for lists. That is every picture an alarm rule took, and the
     * camera's other pictures (of motion, say) from a few seconds before an
     * alarm to a few seconds after it. With `videos`, the camera's video
     * recordings of that time are noted as well (not copied: see clipState).
     * False when the camera could not be asked.
     */
    private boolean copy(int position, long since, boolean videos) {
        List<Camera> asked = cameras;
        if (position < 0 || position >= asked.size() || asked.get(position).host.isEmpty()) return true;
        Camera camera = asked.get(position);
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);   // the camera's own clock
        synchronized (copying) {
            try {
                Card.Found found = Card.find(camera.host, camera.user, camera.password,
                        stamp.format(new Date(since)), stamp.format(new Date(System.currentTimeMillis() + 600000)), videos);
                List<Card.Shot> shots = found.shots;
                Collections.sort(shots, (a, b) -> a.time.compareTo(b.time));
                long[] taken = new long[shots.size()];
                String[] rules = new String[shots.size()];   // the alarm rules that fired for each picture, joined by +
                List<long[]> alarms = new ArrayList<>();      // each alarm's first and last picture
                for (int i = 0; i < taken.length; i++) {
                    taken[i] = stamp.parse(shots.get(i).time).getTime();
                    List<String> fired = new ArrayList<>(shots.get(i).codes);
                    fired.retainAll(codes);
                    rules[i] = android.text.TextUtils.join("+", fired);
                    if (rules[i].isEmpty()) continue;
                    long[] last = alarms.isEmpty() ? null : alarms.get(alarms.size() - 1);
                    if (last == null || taken[i] - last[1] > ALARM_GAP_MS) alarms.add(new long[] {taken[i], taken[i]});
                    else last[1] = taken[i];
                }
                File small = new File(store, "small");
                small.mkdirs();
                for (int i = taken.length - 1; i >= 0; i--) {
                    if (rules[i].isEmpty() && !around(alarms, taken[i])) continue;
                    Card.Shot shot = shots.get(i);
                    String name = taken[i] + "_" + position + "_" + rules[i]
                            + "_" + Card.md5(camera.host + shot.path).substring(0, 12) + ".jpg";
                    File full = new File(store, name);
                    if (full.isFile() || !KEPT.matcher(name).matches()) continue;
                    byte[] jpeg = load(camera, shot.path);
                    if (jpeg == null) continue;
                    write(new File(small, name), shrunk(jpeg));
                    write(full, jpeg);
                }
                if (videos) {
                    List<Recording> noted = new ArrayList<>();
                    for (Card.Clip clip : found.clips) {
                        if (!clip.path.toLowerCase(Locale.ROOT).endsWith(".mp4")) continue;   // the kind the page can play
                        long start = stamp.parse(clip.start).getTime(), end = stamp.parse(clip.end).getTime();
                        noted.add(new Recording(start, end, clip.path, clip.bytes, start + "_" + position + "_"
                                + Math.max(1, (end - start) / 1000) + "_" + Card.md5(camera.host + clip.path).substring(0, 12) + ".mp4"));
                    }
                    Collections.sort(noted, (a, b) -> Long.compare(a.start, b.start));
                    Map<Integer, List<Recording>> all = new HashMap<>(recordings);
                    all.put(position, noted);
                    recordings = all;
                }
                return true;
            } catch (IOException | JSONException | ParseException | RuntimeException unanswered) {
                Log.w(TAG, "camera-" + position + ": its card could not be read: " + unanswered);
                return false;
            }
        }
    }

    /** True when that moment is within a few seconds of one of those alarms. */
    private boolean around(List<long[]> alarms, long moment) {
        for (long[] alarm : alarms) {
            if (moment >= alarm[0] - aroundMs && moment <= alarm[1] + aroundMs) return true;
        }
        return false;
    }

    /** Where the camera hands out a file of its card, by the file's place there. */
    private static String onCard(Camera camera, String path) {
        StringBuilder address = new StringBuilder("http://" + camera.host + "/cgi-bin/RPC_Loadfile");
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (c < 128 && (Character.isLetterOrDigit(c) || "/_.-~".indexOf(c) >= 0)) address.append(c);
            else address.append(String.format(Locale.ROOT, "%%%02X", b & 0xff));
        }
        return address.toString();
    }

    /** One picture from a camera's card, by its place there; null when the camera does not give it. */
    private static byte[] load(Camera camera, String path) throws IOException {
        HttpURLConnection reply = camera.login.get(onCard(camera, path), SNAPSHOT_TIMEOUT_MS);
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
                    if (!copy(position, now - KEPT_MS, true)) unanswered.add(position);
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
                File videos = new File(store, "video");
                String[] watched = videos.list();
                for (int i = 0; watched != null && i < watched.length; i++) {
                    Matcher told = CLIP.matcher(watched[i]);
                    boolean old;
                    if (told.matches()) {
                        old = Long.parseLong(told.group(1)) < now - KEPT_MS;
                    } else {   // what an interrupted copy left behind
                        synchronized (fetches) {
                            old = !fetches.containsKey(watched[i].replace(".part", ""));
                        }
                    }
                    if (old) new File(videos, watched[i]).delete();
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
