package home.wallclock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Checks the remote control's access rules on a PC: who is on an allowed
 * network, who gets past the user list, what the server hands out.
 * Run with: python tools/test_remote.py
 */
public final class RemoteTest {
    private static int failed;
    private static final byte[] PICTURE = {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9};
    private static final byte[] VIDEO = new byte[300000];   // bigger than one piece of the sending
    private static File video;

    /** Stands in for the app. */
    private static final class FakeHost implements Remote.Host {
        final Remote.Access access = new Remote.Access();
        String command = "", saved = "", asked = "";

        public Remote.Access access() {
            return access;
        }

        public InputStream asset(String name) throws IOException {
            if (name.equals("remote.html")) return new ByteArrayInputStream("THE REMOTE PAGE".getBytes(StandardCharsets.UTF_8));
            if (name.equals("index.html")) return new ByteArrayInputStream("<html><body>KIOSK</body></html>".getBytes(StandardCharsets.UTF_8));
            throw new FileNotFoundException(name);
        }

        public String config() {
            return "{\"locale\":\"en-CA\"}";
        }

        public void saveConfig(String json) {
            saved = json;
        }

        public void command(String json) {
            command = json;
        }

        public String media() {
            return "[]";
        }

        public String slideFolders() {
            return "[\"night\"]";
        }

        public String state() {
            return "{\"view\":\"clock\"}";
        }

        public String alarms() {
            return "{\"alarms\":[{\"name\":\"Driveway\"}],\"failed\":[]}";
        }

        public byte[] shot(String file, boolean small) {
            asked = file + " " + (small ? "small" : "full");
            return PICTURE;
        }

        public String clipState(String file) {
            asked = file + " wanted";
            return "{\"done\":true}";
        }

        public File clip(String file) {
            asked = file + " sent";
            return video;
        }

        public String secret() {
            return "test secret";
        }

        public String printer() {
            return "{\"online\":true,\"gcode_state\":\"RUNNING\"}";
        }

        public byte[] printerPicture() {
            return PICTURE;
        }

        public void log(String message) {
        }
    }

    private static final class Reply {
        int status;
        String type = "";
        byte[] body = new byte[0];

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < VIDEO.length; i++) VIDEO[i] = (byte) (i * 31);
        video = File.createTempFile("remote-test", ".mp4");
        video.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(video)) {
            out.write(VIDEO);
        }
        networks();
        server();
        sessions();
        System.out.println(failed == 0 ? "\nAll passed." : "\n" + failed + " FAILED.");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void networks() throws Exception {
        InetAddress tablet = InetAddress.getByName("192.168.1.20");
        Remote.Access home = new Remote.Access();
        check("the tablet's own /24 is let in", Remote.allowed(ip("192.168.1.7"), tablet, home));
        check("another subnet of the house is not", !Remote.allowed(ip("192.168.2.7"), tablet, home));
        check("the Internet is not", !Remote.allowed(ip("8.8.8.8"), tablet, home));
        home.also = "10.8.0.0/24, 172.16.5.9";
        check("a listed VPN subnet is let in", Remote.allowed(ip("10.8.0.33"), tablet, home));
        check("an address just outside it is not", !Remote.allowed(ip("10.8.1.33"), tablet, home));
        check("a listed single address is let in", Remote.allowed(ip("172.16.5.9"), tablet, home));
        check("its neighbour is not", !Remote.allowed(ip("172.16.5.10"), tablet, home));
        home.lan = false;
        check("home network switched off: its /24 is refused", !Remote.allowed(ip("192.168.1.7"), tablet, home));
        check("...while a listed network still gets in", Remote.allowed(ip("10.8.0.33"), tablet, home));
        Remote.Access wide = new Remote.Access();
        wide.lan = false;
        wide.also = "0.0.0.0/0; 10.0.0.0/4 junk 10.8.0.0/33 999.1.1.1 example.com";
        check("catch-all and malformed networks let nobody in",
                !Remote.allowed(ip("8.8.8.8"), tablet, wide) && !Remote.allowed(ip("10.1.1.1"), tablet, wide));
        InetAddress six = InetAddress.getByName("::1");
        check("IPv6 is refused", !Remote.allowed(six, six, new Remote.Access()));
    }

    private static void server() throws Exception {
        FakeHost host = new FakeHost();
        host.access.users.put("246810", new Remote.User("Anna", true));
        host.access.users.put("1357", new Remote.User("Иван", false));   // "Ivan" in Cyrillic; may not change the settings
        int port = freePort();
        Remote remote = new Remote(port, host);
        remote.setDaemon(true);
        remote.start();
        Thread.sleep(300);

        check("the remote page itself needs no PIN", get(port, "/", null, null).text().equals("THE REMOTE PAGE"));
        check("/settings is the kiosk page, told it is the remote control",
                get(port, "/settings", null, null).text().contains("<body class=\"remote\">KIOSK"));
        check("no file outside the page's own", get(port, "/a/b.js", null, null).status == 404
                && get(port, "/..%2Fsecret", null, null).status == 404);
        check("the API without a PIN: 401", get(port, "/api/state", null, null).status == 401);
        check("the settings without a PIN: 401", get(port, "/api/config", null, null).status == 401);
        check("a wrong PIN: 401", get(port, "/api/state", null, "111111").status == 401);
        Reply state = get(port, "/api/state", null, "246810");
        check("the PIN alone: the state, and who it is", state.status == 200
                && state.text().equals("{\"user\":\"Anna\",\"settings\":true,\"tablet\":{\"view\":\"clock\"}}"));
        check("a name sent along changes nothing", get(port, "/api/state", "bob", "246810").text().startsWith("{\"user\":\"Anna\""));
        check("another user, named in another alphabet", get(port, "/api/state", null, "1357").text().startsWith("{\"user\":\"Иван\",\"settings\":false"));
        check("a user without the settings may not have them, nor save them (403)", get(port, "/api/config", null, "1357").status == 403
                && post(port, "/api/config", null, "1357", "{\"y\":1}").status == 403 && host.saved.isEmpty());
        check("...but may switch the wall clock's page", post(port, "/api/command", null, "1357", "{\"view\":\"camera\"}").status == 200
                && host.command.equals("{\"view\":\"camera\"}"));
        check("a command reaches the app", post(port, "/api/command", null, "246810", "{\"view\":\"news\"}").status == 200
                && host.command.equals("{\"view\":\"news\"}"));
        check("a command without the PIN does not", post(port, "/api/command", null, "", "{\"view\":\"slides\"}").status == 401
                && host.command.equals("{\"view\":\"news\"}"));
        check("the settings are saved only with the PIN", post(port, "/api/config", null, null, "{\"x\":1}").status == 401
                && host.saved.isEmpty() && post(port, "/api/config", null, "246810", "{\"x\":1}").status == 200
                && host.saved.equals("{\"x\":1}"));
        check("the alarm list is handed on", get(port, "/api/events", null, "246810").text().contains("Driveway"));
        check("the printer's state and picture are handed on, with the PIN only", get(port, "/api/printer", null, "246810").text().contains("RUNNING")
                && Arrays.equals(get(port, "/api/printer/camera", null, "246810").body, PICTURE)
                && get(port, "/api/printer", null, null).status == 401 && get(port, "/api/printer/camera", null, null).status == 401);
        check("the slide folders are handed on, with the PIN only", get(port, "/api/folders", null, "246810").text().equals("[\"night\"]")
                && get(port, "/api/folders", null, null).status == 401);
        String file = "1791654912000_0_CrossLineDetection%2BCrossRegionDetection_0A1B2C3D4E5F.jpg";
        Reply picture = get(port, "/api/shot?size=thumb&file=" + file, null, "246810");
        check("a picture comes as a JPEG", picture.status == 200 && picture.type.startsWith("image/jpeg")
                && Arrays.equals(picture.body, PICTURE)
                && host.asked.equals("1791654912000_0_CrossLineDetection+CrossRegionDetection_0A1B2C3D4E5F.jpg small"));
        check("a picture needs the PIN too", get(port, "/api/shot?size=thumb&file=" + file, null, null).status == 401);
        host.asked = "";
        check("only the tablet's alarm pictures can be asked for",
                get(port, "/api/shot?size=thumb&file=..%2F..%2Fshared_prefs%2Fkiosk.xml", null, "246810").status == 404
                && get(port, "/api/shot?size=thumb&file=%2Fetc%2Fpasswd.jpg", null, "246810").status == 404
                && get(port, "/api/shot?size=thumb&file=small%2F" + file, null, "246810").status == 404
                && get(port, "/api/shot?size=huge&file=" + file, null, "246810").status == 404
                && host.asked.isEmpty());
        String clip = "1791654912000_0_65_0A1B2C3D4E5F.mp4";
        host.asked = "";
        check("a video needs the PIN: to be fetched, and to be had", get(port, "/api/clip/get?file=" + clip, null, null).status == 401
                && get(port, "/api/clip?file=" + clip, null, null).status == 401 && host.asked.isEmpty());
        check("a video is fetched when asked for", get(port, "/api/clip/get?file=" + clip, null, "246810").text().equals("{\"done\":true}")
                && host.asked.equals(clip + " wanted"));
        Reply film = get(port, "/api/clip?file=" + clip, null, "246810");
        check("the video comes whole, as MP4", film.status == 200 && film.type.startsWith("video/mp4")
                && Arrays.equals(film.body, VIDEO) && host.asked.equals(clip + " sent"));
        host.asked = "";
        check("only an alarm's video can be asked for",
                get(port, "/api/clip?file=..%2F..%2Fshared_prefs%2Fkiosk.xml", null, "246810").status == 404
                && get(port, "/api/clip?file=%2Fsdcard%2Fmovie.mp4", null, "246810").status == 404
                && get(port, "/api/clip?file=video%2F" + clip, null, "246810").status == 404
                && get(port, "/api/clip/get?file=..%2F" + clip, null, "246810").text().contains("failed")
                && host.asked.isEmpty());
        check("an unknown API address: 404", get(port, "/api/secrets", null, "246810").status == 404);

        for (int i = 0; i < 5; i++) get(port, "/api/state", null, "000000");
        check("five wrong PINs lock even the right one out (429)", get(port, "/api/state", null, "246810").status == 429);

        Remote second = new Remote(port, host);   // while the first still has the port
        second.setDaemon(true);
        second.start();
        second.join(2000);
        check("a port already taken is reported", second.busy() && !remote.busy() && second.port() == port);

        host.access.lan = false;   // this test comes from 127.0.0.1, the "home network" of 127.0.0.1
        check("home network switched off: not even the page", get(port, "/", null, null).status == 403);
        host.access.lan = true;
        remote.close();

        FakeHost empty = new FakeHost();
        int other = freePort();
        Remote off = new Remote(other, empty);
        off.setDaemon(true);
        off.start();
        Thread.sleep(300);
        check("nobody on the list: the remote control is off (403)", get(other, "/api/state", null, "246810").status == 403);
        off.close();
    }

    private static String sessionOf(int port, String pin) throws IOException {
        return new String(post(port, "/api/session", null, pin, "").body, StandardCharsets.UTF_8)
                .replaceAll(".*\"session\":\"([^\"]+)\".*", "$1");
    }

    private static void sessions() throws Exception {
        FakeHost host = new FakeHost();
        host.access.users.put("246810", new Remote.User("Anna", true));
        host.access.users.put("1357", new Remote.User("Иван", false));
        long[] now = {1_800_000_000_000L};
        int port = freePort();
        Remote remote = new Remote(port, host);
        remote.clock = () -> now[0];
        remote.setDaemon(true);
        remote.start();
        Thread.sleep(300);
        long hour = 3600000L;

        check("a wrong PIN gets no session", post(port, "/api/session", null, "000000", "").status == 401);
        check("a session cannot be had with a session", call(port, "/api/session", null, null, "", "anything").status == 401);
        Reply signed = post(port, "/api/session", null, "246810", "");
        check("signing in says who it is and what they may", signed.text().contains("\"user\":\"Anna\",\"settings\":true"));
        String session = sessionOf(port, "246810");
        Reply state = call(port, "/api/state", null, null, null, session);
        check("signed in, the session alone opens the API", state.status == 200 && state.text().startsWith("{\"user\":\"Anna\""));
        String ivan = sessionOf(port, "1357");
        check("a session keeps its user's rights", call(port, "/api/state", null, null, null, ivan).text().startsWith("{\"user\":\"Иван\",\"settings\":false")
                && call(port, "/api/config", null, null, null, ivan).status == 403 && call(port, "/api/config", null, null, null, session).status == 200);
        check("a forged session is refused", call(port, "/api/state", null, null, null, session.substring(0, session.length() - 2) + "00").status == 401
                && call(port, "/api/state", null, null, null, (now[0] - 1) + ":" + session.split(":")[1]).status == 401
                && call(port, "/api/state", null, null, null, "Anna:" + session).status == 401);
        now[0] += 7 * hour;
        check("used within eight hours, it goes on", call(port, "/api/state", null, null, null, session).status == 200);
        now[0] += 8 * hour + 1;
        check("eight hours unused, it has ended", call(port, "/api/state", null, null, null, session).status == 401);
        now[0] = 1_800_000_000_000L;
        String daily = sessionOf(port, "246810");
        boolean kept = true;
        for (int i = 0; i < 3; i++) {
            now[0] += 7 * hour;
            kept &= call(port, "/api/state", null, null, null, daily).status == 200;
        }
        now[0] += 3 * hour + 1;
        check("however much it is used, a session ends after 24 hours", kept && call(port, "/api/state", null, null, null, daily).status == 401);
        String other = sessionOf(port, "246810");
        check("signing out ends the session", call(port, "/api/session/end", null, null, "", other).status == 200
                && call(port, "/api/state", null, null, null, other).status == 401);
        String before = sessionOf(port, "246810");
        host.access.users.remove("246810");
        host.access.users.put("999999", new Remote.User("Anna", true));
        check("a new PIN ends the user's sessions", call(port, "/api/state", null, null, null, before).status == 401);
        host.access.users.clear();
        check("with nobody listed, no session works (403)", call(port, "/api/state", null, null, null, ivan).status == 403);
        remote.close();
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok      " : "FAILED  ") + what);
        if (!ok) failed++;
    }

    private static InetAddress ip(String address) throws Exception {
        return InetAddress.getByName(address);
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static Reply get(int port, String path, String user, String pin) throws IOException {
        return call(port, path, user, pin, null);
    }

    private static Reply post(int port, String path, String user, String pin, String body) throws IOException {
        return call(port, path, user, pin, body);
    }

    private static Reply call(int port, String path, String user, String pin, String body) throws IOException {
        return call(port, path, user, pin, body, null);
    }

    private static Reply call(int port, String path, String user, String pin, String body, String session) throws IOException {
        HttpURLConnection link = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        link.setConnectTimeout(3000);
        link.setReadTimeout(5000);
        if (user != null) link.setRequestProperty("X-User", URLEncoder.encode(user, "UTF-8"));
        if (pin != null) link.setRequestProperty("X-Pin", pin);
        if (session != null) link.setRequestProperty("X-Session", session);
        if (body != null) {
            link.setDoOutput(true);
            link.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        Reply reply = new Reply();
        reply.status = link.getResponseCode();
        reply.type = link.getContentType() == null ? "" : link.getContentType();
        try (InputStream in = reply.status < 400 ? link.getInputStream() : link.getErrorStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n = in == null ? -1 : in.read(chunk); n > 0; n = in.read(chunk)) bytes.write(chunk, 0, n);
            reply.body = bytes.toByteArray();
        }
        return reply;
    }
}
