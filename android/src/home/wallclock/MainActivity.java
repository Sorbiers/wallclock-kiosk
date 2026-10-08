package home.wallclock;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
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
import java.util.Arrays;
import java.util.Collections;

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
    private Remote remote;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);   // the page keeps the last forecast in localStorage
        settings.setAllowFileAccess(false);
        settings.setTextZoom(100);             // the page is sized in screen units, not system font size
        settings.setMediaPlaybackRequiresUserGesture(false);   // alarm sounds start by themselves
        WebView.setWebContentsDebuggingEnabled(true);   // chrome://inspect over USB

        cameras = new Cameras(this::runInPage);
        web.setWebViewClient(new PageClient(getAssets(), cameras, slides()));
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl(PAGE);

        remote = new Remote(getAssets(), new RemoteHost());
        remote.start();
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

    private void runInPage(String javascript) {
        runOnUiThread(() -> web.evaluateJavascript(javascript, null));
    }

    private SharedPreferences settings() {
        return getSharedPreferences("kiosk", MODE_PRIVATE);
    }

    /**
     * The slideshow pictures: the app's own folder on shared storage, which
     * needs no permission and can be filled over USB (sync-slides.bat):
     * /sdcard/Android/data/home.wallclock/files/slides
     */
    private File slides() {
        return getExternalFilesDir("slides");
    }

    /** What the remote-control page (another device's browser) may do. */
    private final class RemoteHost implements Remote.Host {
        /** The settings, without the camera password: it never leaves the tablet. */
        @Override
        public String config() {
            try {
                return new JSONObject(settings().getString("config", "{}")).put("camera_password", "").toString();
            } catch (JSONException unreadable) {
                return "";
            }
        }

        /** An empty password means "unchanged". Then the page restarts with the new settings. */
        @Override
        public void saveConfig(String json) {
            try {
                JSONObject next = new JSONObject(json);
                if (next.optString("camera_password").isEmpty()) {
                    JSONObject current = new JSONObject(settings().getString("config", "{}"));
                    next.put("camera_password", current.optString("camera_password"));
                }
                settings().edit().putString("config", next.toString()).apply();
                runInPage("location.reload()");
            } catch (JSONException rejected) {
                // not settings: ignore
            }
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
                        : Math.max(0.01f, Math.min(1f, level));
                getWindow().setAttributes(window);
            });
        }

        /** File names of the slideshow pictures, as a JSON list. */
        @JavascriptInterface
        public String listSlides() {
            JSONArray names = new JSONArray();
            String[] found = slides() == null ? null : slides().list();
            if (found != null) {
                Arrays.sort(found);
                for (String name : found) {
                    if (PageClient.isPicture(name)) names.put(name);
                }
            }
            return names.toString();
        }

        /** Where the remote control is reached on the home network, or "" without Wi-Fi. */
        @JavascriptInterface
        public String remoteAddress() {
            try {
                for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InetAddress address : Collections.list(network.getInetAddresses())) {
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                            return "http://" + address.getHostAddress() + ":" + Remote.PORT;
                        }
                    }
                }
            } catch (SocketException ignored) {
                // no network
            }
            return "";
        }

        @JavascriptInterface
        public void openSystemSettings() {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }
}
