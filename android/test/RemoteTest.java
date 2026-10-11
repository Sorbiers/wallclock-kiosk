package home.wallclock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
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
        networks();
        server();
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
        host.access.users.put("anna", "246810");
        host.access.users.put("иван", "1357");   // "ivan" in Cyrillic
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
        check("the API without a name and PIN: 401", get(port, "/api/state", null, null).status == 401);
        check("the settings without a name and PIN: 401", get(port, "/api/config", null, null).status == 401);
        check("a wrong PIN: 401", get(port, "/api/state", "anna", "111111").status == 401);
        check("a name not on the list: 401", get(port, "/api/state", "bob", "246810").status == 401);
        check("another user's PIN: 401", get(port, "/api/state", "anna", "1357").status == 401);
        Reply state = get(port, "/api/state", "Anna", "246810");
        check("the right name (any case) and PIN: the state", state.status == 200
                && state.text().equals("{\"user\":\"Anna\",\"tablet\":{\"view\":\"clock\"}}"));
        check("a name in another alphabet", get(port, "/api/state", "Иван", "1357").status == 200);
        check("a command reaches the app", post(port, "/api/command", "anna", "246810", "{\"view\":\"news\"}").status == 200
                && host.command.equals("{\"view\":\"news\"}"));
        check("a command without the PIN does not", post(port, "/api/command", "anna", "", "{\"view\":\"slides\"}").status == 401
                && host.command.equals("{\"view\":\"news\"}"));
        check("the settings are saved only with the PIN", post(port, "/api/config", null, null, "{\"x\":1}").status == 401
                && host.saved.isEmpty() && post(port, "/api/config", "anna", "246810", "{\"x\":1}").status == 200
                && host.saved.equals("{\"x\":1}"));
        check("the alarm list is handed on", get(port, "/api/events", "anna", "246810").text().contains("Driveway"));
        String file = "1791654912000_0_CrossLineDetection%2BCrossRegionDetection_0A1B2C3D4E5F.jpg";
        Reply picture = get(port, "/api/shot?size=thumb&file=" + file, "anna", "246810");
        check("a picture comes as a JPEG", picture.status == 200 && picture.type.startsWith("image/jpeg")
                && Arrays.equals(picture.body, PICTURE)
                && host.asked.equals("1791654912000_0_CrossLineDetection+CrossRegionDetection_0A1B2C3D4E5F.jpg small"));
        check("a picture needs the PIN too", get(port, "/api/shot?size=thumb&file=" + file, null, null).status == 401);
        host.asked = "";
        check("only the tablet's alarm pictures can be asked for",
                get(port, "/api/shot?size=thumb&file=..%2F..%2Fshared_prefs%2Fkiosk.xml", "anna", "246810").status == 404
                && get(port, "/api/shot?size=thumb&file=%2Fetc%2Fpasswd.jpg", "anna", "246810").status == 404
                && get(port, "/api/shot?size=thumb&file=small%2F" + file, "anna", "246810").status == 404
                && get(port, "/api/shot?size=huge&file=" + file, "anna", "246810").status == 404
                && host.asked.isEmpty());
        check("an unknown API address: 404", get(port, "/api/secrets", "anna", "246810").status == 404);

        for (int i = 0; i < 5; i++) get(port, "/api/state", "anna", "000000");
        check("five wrong PINs lock even the right one out (429)", get(port, "/api/state", "anna", "246810").status == 429);

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
        check("nobody on the list: the remote control is off (403)", get(other, "/api/state", "anna", "246810").status == 403);
        off.close();
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
        HttpURLConnection link = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        link.setConnectTimeout(3000);
        link.setReadTimeout(5000);
        if (user != null) link.setRequestProperty("X-User", URLEncoder.encode(user, "UTF-8"));
        if (pin != null) link.setRequestProperty("X-Pin", pin);
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
