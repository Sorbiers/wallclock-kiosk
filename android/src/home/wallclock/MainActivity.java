package home.wallclock;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/**
 * The whole app: one full-screen WebView showing the page bundled in assets.
 *
 * The page (web/) draws everything. This side does what a web page may not:
 * keep the screen on and full screen, set the backlight, store the settings,
 * and talk to the cameras with a login (see {@link Cameras}).
 */
public class MainActivity extends Activity {
    /** The page's address. Requests to it never leave the app (see {@link PageClient}). */
    static final String PAGE = "http://localhost/";

    private WebView web;
    private Cameras cameras;
    private volatile Remote remote;
    private volatile float lux = -1f;   // room light from the sensor; below 0 = not known
    private volatile String pageState = "{}";   // what the page says it is doing, for the remote page
    private long startedAt;
    private int volumeBefore = -1;      // the tablet's media volume to put back after an alarm; below 0 = untouched

    private final SensorEventListener light = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent reading) {
            lux = reading.values[0];
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        startedAt = SystemClock.elapsedRealtime();
        // Back from a restart of its own: the backlight as it was (no bright flash at night), the same view.
        float backlight = getIntent().getFloatExtra("brightness", -1f);
        if (backlight >= 0) {
            WindowManager.LayoutParams window = getWindow().getAttributes();
            window.screenBrightness = Math.min(1f, backlight);
            getWindow().setAttributes(window);
        }
        String view = getIntent().getStringExtra("view");

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);   // the page keeps the last forecast in localStorage
        settings.setAllowFileAccess(false);
        settings.setTextZoom(100);             // the page is sized in screen units, not system font size
        settings.setMediaPlaybackRequiresUserGesture(false);   // alarm sounds start by themselves
        // chrome://inspect over USB, in debug builds only (build.bat --debug)
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);

        cameras = new Cameras(this::runInPage, new File(getFilesDir(), "alarms"));
        web.setWebViewClient(new PageClient(getAssets(), cameras, slides(), media(), this::pageDied));
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl(view == null ? PAGE : PAGE + "?view=" + view);

        listen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SensorManager sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor sensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (sensor != null) sensors.registerListener(light, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        ((SensorManager) getSystemService(SENSOR_SERVICE)).unregisterListener(light);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            bars.hide(WindowInsets.Type.systemBars());
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** Back never leaves the kiosk; it returns the page to the clock. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        runInPage("window.kioskNative && kioskNative.back()");
    }

    @Override
    protected void onDestroy() {
        new Thread(cameras::stop).start();   // closing sockets is not allowed on the main thread
        new Thread(remote::close).start();
        web.destroy();
        super.onDestroy();
    }

    /** Start the whole app afresh, in a new process, on the given view (null: the clock). The settings stay. */
    private void restart(String view) {
        Intent again = Intent.makeRestartActivityTask(getComponentName());
        if (view != null && view.matches("[a-z]+")) again.putExtra("view", view);
        again.putExtra("brightness", getWindow().getAttributes().screenBrightness);
        startActivity(again);
        Runtime.getRuntime().exit(0);
    }

    /** The page's process died: start afresh. Not at once if it died right after starting, or it could spin. */
    private void pageDied() {
        long up = SystemClock.elapsedRealtime() - startedAt;
        new Handler(Looper.getMainLooper()).postDelayed(() -> restart(null), up < 20000 ? 30000 : 0);
    }

    private void runInPage(String javascript) {
        runOnUiThread(() -> web.evaluateJavascript(javascript, null));
    }

    private SharedPreferences settings() {
        return getSharedPreferences("kiosk", MODE_PRIVATE);
    }

    /** The remote control's port: the one the settings name, when it is one an app may open. */
    private int remotePort() {
        try {
            JSONObject saved = new JSONObject(settings().getString("config", "{}")).optJSONObject("remote");
            int port = saved == null ? 0 : saved.optInt("port");
            if (port >= 1024 && port <= 65535) return port;
        } catch (JSONException unreadable) {
            // the usual port
        }
        return Remote.DEFAULT_PORT;
    }

    /** Start the remote control, or move it when the settings name another port. */
    private synchronized void listen() {
        int port = remotePort();
        if (remote != null && remote.port() == port) return;
        if (remote != null) remote.close();
        remote = new Remote(port, new RemoteHost());
        remote.start();
    }

    /**
     * The slideshow pictures: the app's own folder on shared storage, which
     * needs no permission and can be filled over USB (sync-slides.bat):
     * /sdcard/Android/data/home.wallclock/files/slides
     */
    private File slides() {
        return getExternalFilesDir("slides");
    }

    /** What the alarm clock can play or show (sync-media.bat), next to the slides: .../files/media */
    private File media() {
        return getExternalFilesDir("media");
    }

    /** The names of the pictures, or of everything playable, in one of those folders, as a JSON list. */
    private static String names(File folder, boolean picturesOnly) {
        JSONArray names = new JSONArray();
        String[] found = folder == null ? null : folder.list();
        if (found != null) {
            Arrays.sort(found);
            for (String name : found) {
                if (picturesOnly ? PageClient.isPicture(name) : PageClient.isMedia(name)) names.put(name);
            }
        }
        return names.toString();
    }

    /** The names of the folders inside the slides folder, as a JSON list: day and night may each have one. */
    private static String folders(File slides) {
        JSONArray names = new JSONArray();
        File[] found = slides == null ? null : slides.listFiles();
        if (found != null) {
            Arrays.sort(found);
            for (File one : found) {
                if (one.isDirectory() && PageClient.plain(one.getName())) names.put(one.getName());
            }
        }
        return names.toString();
    }

    /** What the remote-control page (another device's browser) may do. */
    private final class RemoteHost implements Remote.Host {
        private JSONObject saved() throws JSONException {
            return new JSONObject(settings().getString("config", "{}"));
        }

        /** The users of the remote control, in the settings as saved or in the JSON given. */
        private JSONArray users(JSONObject config) {
            JSONObject remote = config.optJSONObject("remote");
            JSONArray users = remote == null ? null : remote.optJSONArray("users");
            return users == null ? new JSONArray() : users;
        }

        @Override
        public Remote.Access access() {
            Remote.Access access = new Remote.Access();
            try {
                JSONObject config = saved(), remote = config.optJSONObject("remote");
                if (remote != null) {
                    access.lan = remote.optBoolean("lan", true);
                    access.also = remote.optString("also");
                }
                JSONArray users = users(config);
                for (int i = 0; i < users.length(); i++) {
                    JSONObject user = users.optJSONObject(i);
                    if (user == null) continue;
                    String name = user.optString("name").trim(), pin = user.optString("pin").trim();
                    if (!name.isEmpty() && !pin.isEmpty()) access.users.put(name.toLowerCase(Locale.ROOT), pin);
                }
                // From before there were users: the one PIN, under the name "home".
                String old = config.optString("remote_pin").trim();
                if (access.users.isEmpty() && !old.isEmpty()) access.users.put("home", old);
            } catch (JSONException unreadable) {
                // nobody gets in
            }
            return access;
        }

        @Override
        public InputStream asset(String name) throws IOException {
            return getAssets().open(name);
        }

        /** The settings, without the camera password and the PINs: they never leave the tablet. */
        @Override
        public String config() {
            try {
                JSONObject config = saved().put("camera_password", "").put("remote_pin", "");
                JSONArray users = users(config);
                for (int i = 0; i < users.length(); i++) {
                    JSONObject user = users.optJSONObject(i);
                    if (user != null) user.put("pin", "");
                }
                return config.toString();
            } catch (JSONException unreadable) {
                return "";
            }
        }

        /**
         * Settings saved from the remote control. An empty camera password
         * means "unchanged". Who may use the remote control (the networks, the
         * users and their PINs) is decided on the tablet only: that part stays
         * as it is, whatever was sent. Then the page restarts with the new settings.
         */
        @Override
        public void saveConfig(String json) {
            try {
                JSONObject next = new JSONObject(json), current = saved();
                if (next.optString("camera_password").isEmpty()) next.put("camera_password", current.optString("camera_password"));
                next.remove("remote");
                if (current.has("remote")) next.put("remote", current.get("remote"));
                next.put("remote_pin", current.optString("remote_pin"));
                settings().edit().putString("config", next.toString()).apply();
                runInPage("location.reload()");
            } catch (JSONException rejected) {
                // not settings: ignore
            }
        }

        /** The camera alarms of the last day, from the tablet's copies of the cameras' own pictures. */
        @Override
        public String alarms() {
            return cameras.alarms();
        }

        @Override
        public byte[] shot(String file, boolean small) {
            return cameras.picture(file, small);
        }

        @Override
        public void log(String message) {
            Log.w("WallClock", message);
        }

        @Override
        public String media() {
            return names(MainActivity.this.media(), false);
        }

        @Override
        public String slideFolders() {
            return folders(slides());
        }

        @Override
        public String state() {
            return pageState;
        }

        @Override
        public void command(String json) {
            try {
                runInPage("window.kioskNative && kioskNative.remote(" + new JSONObject(json) + ")");
            } catch (JSONException rejected) {
                // not a command: ignore
            }
        }
    }

    /** What the page can ask of the app, as window.Android. Called on a WebView thread. */
    private final class Bridge {
        @JavascriptInterface
        public String getConfig() {
            return settings().getString("config", "");
        }

        @JavascriptInterface
        public void saveConfig(String json) {
            settings().edit().putString("config", json).apply();
            listen();   // the remote control's port may have changed
        }

        /** The page's effective settings: (re)start listening to the cameras with them. */
        @JavascriptInterface
        public void configure(String json) {
            cameras.configure(json);
        }

        /** Backlight from 0 to 1; below 0 hands it back to Android. */
        @JavascriptInterface
        public void setBrightness(final float level) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams window = getWindow().getAttributes();
                window.screenBrightness = level < 0
                        ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        : Math.max(0f, Math.min(1f, level));   // 0 = the panel's minimum, not off
                getWindow().setAttributes(window);
            });
        }

        /** Room light in lux, or below 0 when the tablet has no light sensor. */
        @JavascriptInterface
        public float lux() {
            return lux;
        }

        /** File names of the slideshow pictures, in the slides folder itself ("") or a folder inside it, as a JSON list. */
        @JavascriptInterface
        public String listSlides(String folder) {
            return names(PageClient.inside(slides(), folder), true);
        }

        /** The folders inside the slides folder, as a JSON list of names. */
        @JavascriptInterface
        public String listSlideFolders() {
            return folders(slides());
        }

        /** File names of what the alarm clock can play or show, as a JSON list. */
        @JavascriptInterface
        public String listMedia() {
            return names(media(), false);
        }

        /** Where the remote control is reached on the home network, or "" without Wi-Fi. */
        @JavascriptInterface
        public String remoteAddress() {
            try {
                for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InetAddress address : Collections.list(network.getInetAddresses())) {
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                            return "http://" + address.getHostAddress() + ":" + remote.port();
                        }
                    }
                }
            } catch (SocketException ignored) {
                // no network
            }
            return "";
        }

        /** True when the remote control could not open its port: something else on the tablet has it. */
        @JavascriptInterface
        public boolean remotePortBusy() {
            return remote.busy();
        }

        @JavascriptInterface
        public void openSystemSettings() {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }

        /**
         * Media volume as a share of the loudest the tablet can do, for as long
         * as an alarm rings; below 0 puts back what it was before.
         */
        @JavascriptInterface
        public synchronized void setVolume(float share) {
            try {
                AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (share < 0) {
                    if (volumeBefore >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0);
                    volumeBefore = -1;
                    return;
                }
                if (volumeBefore < 0) volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                int loudest = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int level = Math.round(Math.min(1f, share) * loudest);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, share > 0 ? Math.max(1, level) : 0, 0);
            } catch (RuntimeException refused) {
                // the tablet keeps its own volume
            }
        }

        /** Forget the pictures the page keeps in memory, so the slideshow reads its folder again. */
        @JavascriptInterface
        public void clearCache() {
            runOnUiThread(() -> web.clearCache(true));
        }

        /** Start the whole app afresh; it comes back on `view` (empty: the clock). */
        @JavascriptInterface
        public void restart(final String view) {
            runOnUiThread(() -> MainActivity.this.restart(view == null || view.isEmpty() ? null : view));
        }

        /** What the page is doing, as JSON, kept for the remote page. */
        @JavascriptInterface
        public void report(String json) {
            pageState = json;
        }

        /** When this app was built and from which commit (set by the build): tells builds apart. */
        @JavascriptInterface
        public String build() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException impossible) {
                return "";
            }
        }
    }
}
