package home.wallclock;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP Digest login for one camera (Android's HttpURLConnection has none).
 * Remembers the camera's challenge, so only the first request costs a round trip.
 */
final class Digest {
    private static final Pattern FIELD = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))");
    private static final int CONNECT_TIMEOUT_MS = 8000;

    private final String user;
    private final String password;
    private final SecureRandom random = new SecureRandom();
    private Map<String, String> challenge;
    private int uses;

    Digest(String user, String password) {
        this.user = user;
        this.password = password;
    }

    /** GET the address, logged in. The caller reads the reply and disconnects. */
    HttpURLConnection get(String address, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = open(address, readTimeoutMs);
        if (connection.getResponseCode() != HttpURLConnection.HTTP_UNAUTHORIZED) return connection;
        String header = connection.getHeaderField("WWW-Authenticate");
        connection.disconnect();
        if (header == null || !header.startsWith("Digest")) throw new IOException("Camera asked for an unknown login");
        remember(header);
        return open(address, readTimeoutMs);   // 401 again means the login is wrong
    }

    private HttpURLConnection open(String address, int readTimeoutMs) throws IOException {
        URL url = new URL(address);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        String authorization = authorization("GET", url.getFile());
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        return connection;
    }

    private synchronized void remember(String header) {
        Map<String, String> fields = new HashMap<>();
        Matcher match = FIELD.matcher(header);
        while (match.find()) fields.put(match.group(1), match.group(2) != null ? match.group(2) : match.group(3));
        challenge = fields;
        uses = 0;
    }

    private synchronized String authorization(String method, String uri) {
        if (challenge == null) return null;
        String realm = challenge.get("realm");
        String nonce = challenge.get("nonce");
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        StringBuilder out = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri).append('"');
        String qop = challenge.get("qop");
        if (qop != null && qop.contains("auth")) {
            String count = String.format("%08x", ++uses);
            String clientNonce = Long.toHexString(random.nextLong());
            out.append(", qop=auth, nc=").append(count).append(", cnonce=\"").append(clientNonce).append('"')
                    .append(", response=\"")
                    .append(md5(ha1 + ":" + nonce + ":" + count + ":" + clientNonce + ":auth:" + ha2)).append('"');
        } else {
            out.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        String opaque = challenge.get("opaque");
        if (opaque != null) out.append(", opaque=\"").append(opaque).append('"');
        return out.toString();
    }

    private static String md5(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
