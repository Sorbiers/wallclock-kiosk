package home.wallclock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The pictures an Amcrest/Dahua camera keeps on its SD card when one of its
 * rules fires, found the way the camera's own web page finds them: the RPC2
 * JSON interface (log in by challenge and response, then "mediaFileFind").
 * Read-only: nothing on the camera is changed. The login needs the right to
 * search the camera's recordings.
 */
final class Card {
    /** One stored picture: when it was taken (camera time, "yyyy-MM-dd HH:mm:ss"), which rules fired, where it is. */
    static final class Shot {
        final String time;
        final String path;
        final List<String> codes;

        Shot(String time, String path, List<String> codes) {
            this.time = time;
            this.path = path;
            this.codes = codes;
        }
    }

    private static final int MAX_SCANNED = 4000;   // files looked at per search
    private static final int MAX_FOUND = 600;

    private final String host;
    private Object session;
    private int calls;

    private Card(String host) {
        this.host = host;
    }

    /** The pictures taken between the two times for any of the given rules, in no particular order. */
    static List<Shot> find(String host, String user, String password, String from, String to, Set<String> codes)
            throws IOException, JSONException {
        Card card = new Card(host);
        card.login(user, password);
        try {
            return card.search(from, to, codes);
        } finally {
            card.quietly("/RPC2", "global.logout", null, null);   // cameras allow only so many sessions
        }
    }

    private void login(String user, String password) throws IOException, JSONException {
        JSONObject asked = new JSONObject().put("userName", user).put("password", "").put("clientType", "Web3.0");
        JSONObject challenge = call("/RPC2_Login", "global.login", asked, null);
        JSONObject given = challenge.optJSONObject("params");
        session = challenge.opt("session");
        if (given == null || given.optString("realm").isEmpty() || given.optString("random").isEmpty()) {
            throw new IOException("Camera gave no login challenge: " + challenge.opt("error"));
        }
        String inner = md5(user + ":" + given.getString("realm") + ":" + password);
        JSONObject answer = new JSONObject().put("userName", user).put("clientType", "Web3.0").put("authorityType", "Default")
                .put("password", md5(user + ":" + given.getString("random") + ":" + inner));
        JSONObject result = call("/RPC2_Login", "global.login", answer, null);
        if (!result.optBoolean("result")) throw new IOException("Camera refused the login: " + result.opt("error"));
    }

    private List<Shot> search(String from, String to, Set<String> codes) throws IOException, JSONException {
        Object finder = call("/RPC2", "mediaFileFind.factory.create", null, null).opt("result");
        if (finder == null || JSONObject.NULL.equals(finder)) throw new IOException("Camera would not search its card");
        List<Shot> found = new ArrayList<>();
        try {
            JSONObject condition = new JSONObject().put("Channel", 0).put("StartTime", from).put("EndTime", to)
                    .put("Types", new JSONArray().put("jpg")).put("Order", "Descent");
            call("/RPC2", "mediaFileFind.findFile", new JSONObject().put("condition", condition), finder);
            for (int scanned = 0; scanned < MAX_SCANNED && found.size() < MAX_FOUND; ) {
                JSONObject next = call("/RPC2", "mediaFileFind.findNextFile", new JSONObject().put("count", 100), finder);
                JSONObject params = next.optJSONObject("params");
                JSONArray infos = params == null ? null : params.optJSONArray("infos");
                if (infos == null || infos.length() == 0) break;
                scanned += infos.length();
                for (int i = 0; i < infos.length(); i++) {
                    JSONObject info = infos.getJSONObject(i);
                    JSONArray events = info.optJSONArray("Events");
                    List<String> fired = new ArrayList<>();
                    for (int e = 0; events != null && e < events.length(); e++) {
                        if (codes.contains(events.optString(e))) fired.add(events.optString(e));
                    }
                    if (!fired.isEmpty() && !info.optString("FilePath").isEmpty()) {
                        found.add(new Shot(info.optString("StartTime"), info.optString("FilePath"), fired));
                    }
                }
            }
        } finally {
            quietly("/RPC2", "mediaFileFind.close", null, finder);
            quietly("/RPC2", "mediaFileFind.destroy", null, finder);
        }
        return found;
    }

    private void quietly(String page, String method, Object params, Object object) {
        try {
            call(page, method, params, object);
        } catch (IOException | JSONException ignored) {
            // tidying up only
        }
    }

    private JSONObject call(String page, String method, Object params, Object object) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("method", method).put("params", params == null ? JSONObject.NULL : params).put("id", ++calls);
        if (session != null) body.put("session", session);
        if (object != null) body.put("object", object);
        HttpURLConnection link = (HttpURLConnection) new URL("http://" + host + page).openConnection();
        try {
            link.setConnectTimeout(8000);
            link.setReadTimeout(20000);
            link.setDoOutput(true);
            link.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = link.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream in = link.getResponseCode() < 400 ? link.getInputStream() : link.getErrorStream()) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int n = in == null ? -1 : in.read(chunk); n > 0; n = in.read(chunk)) text.write(chunk, 0, n);
                return new JSONObject(new String(text.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            link.disconnect();
        }
    }

    /** MD5 in upper-case hex, as the camera's login wants it. */
    static String md5(String text) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format(Locale.ROOT, "%02X", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
