package home.wallclock;

import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers the page's own requests from inside the app: its files come from the
 * bundled assets, "/cam/..." is fetched from a camera with the login (a fresh
 * snapshot, or the alarms and pictures on its card), and
 * "/slides/..." comes from the slideshow folder (or a folder inside it) and "/media/..." from the
 * folder of what the alarm clock plays. "/feed/..." is fetched from the two
 * services the newspaper page uses that a web page is not let to read.
 * Anything else (the weather service) goes to the network as usual.
 */
final class PageClient extends WebViewClient {
    private static final String HOST = Uri.parse(MainActivity.PAGE).getHost();

    private final AssetManager assets;
    private final Cameras cameras;
    private final File slides;
    private final File media;
    private final Runnable pageDied;

    PageClient(AssetManager assets, Cameras cameras, File slides, File media, Runnable pageDied) {
        this.assets = assets;
        this.cameras = cameras;
        this.slides = slides;
        this.media = media;
        this.pageDied = pageDied;
    }

    /** The page's own process is gone (out of memory, a crash): this WebView shows nothing any more. */
    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        Log.w("WallClock", "The page's process is gone" + (detail.didCrash() ? " (crashed)" : " (killed)"));
        pageDied.run();
        return true;   // taken care of: Android need not kill the app for it
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (!HOST.equals(url.getHost())) return null;
        String path = url.getPath() == null ? "/" : url.getPath();
        if (path.startsWith("/cam/")) return cameras.answer(url);
        if (path.startsWith("/slides/")) return slide(url.getPathSegments());
        if (path.startsWith("/media/")) return media(new File(path).getName(), header(request, "Range"));
        if (path.startsWith("/feed/")) return feed(url);
        String file = path.equals("/") ? "index.html" : path.substring(1);
        try {
            return new WebResourceResponse(mimeType(file), "utf-8", assets.open(file));
        } catch (IOException missing) {
            return failure(404, "Not Found");
        }
    }

    /** The kiosk page never navigates anywhere else. (A YouTube alarm plays in a frame inside it.) */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return request.isForMainFrame() && !HOST.equals(request.getUrl().getHost());
    }

    /** "/slides/name" is a picture in the slides folder, "/slides/folder/name" one in a folder inside it; nothing else. */
    private WebResourceResponse slide(List<String> parts) {   // the first part is "slides"
        try {
            String name = parts.get(parts.size() - 1);
            File folder = parts.size() == 2 ? slides : parts.size() == 3 ? inside(slides, parts.get(1)) : null;
            if (folder == null || !plain(name) || !isPicture(name)) return failure(404, "Not Found");
            return new WebResourceResponse(mimeType(name.toLowerCase()), null, new FileInputStream(new File(folder, name)));
        } catch (IOException missing) {
            return failure(404, "Not Found");
        }
    }

    /** The slides folder itself for "", a folder inside it by its plain name, null for anything else. */
    static File inside(File slides, String folder) {
        if (slides == null || folder == null || folder.isEmpty()) return slides;
        return plain(folder) ? new File(slides, folder) : null;
    }

    /** A file's or folder's own name and nothing more: no path in it, nothing hidden. */
    static boolean plain(String name) {
        return !name.isEmpty() && !name.startsWith(".") && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    /**
     * A file for the alarm clock. A player asks for a video in parts ("Range"),
     * also to look ahead in it, so a part is what it gets.
     */
    private WebResourceResponse media(String name, String range) {
        try {
            File file = new File(media, name);
            if (media == null || !isMedia(name) || !file.isFile()) return failure(404, "Not Found");
            String type = mimeType(name.toLowerCase());
            long size = file.length(), from = 0, to = size - 1;
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept-Ranges", "bytes");
            boolean part = range != null && range.startsWith("bytes=");
            if (part) {
                String[] ends = range.substring(6).split("-", 2);
                if (ends[0].isEmpty()) {
                    from = Math.max(0, size - Long.parseLong(ends[1]));   // "-500": the last 500 bytes
                } else {
                    from = Long.parseLong(ends[0]);
                    if (ends.length > 1 && !ends[1].isEmpty()) to = Math.min(to, Long.parseLong(ends[1]));
                }
                if (from > to) return failure(416, "Range Not Satisfiable");
                headers.put("Content-Range", "bytes " + from + "-" + to + "/" + size);
            }
            headers.put("Content-Length", String.valueOf(to - from + 1));
            FileInputStream in = new FileInputStream(file);
            in.getChannel().position(from);
            return new WebResourceResponse(type, null, part ? 206 : 200, part ? "Partial Content" : "OK",
                    headers, new Part(in, to - from + 1));
        } catch (IOException | RuntimeException unreadable) {
            return failure(404, "Not Found");
        }
    }

    /** A stream that ends after `left` bytes. */
    private static final class Part extends FilterInputStream {
        private long left;

        Part(InputStream in, long left) {
            super(in);
            this.left = left;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int value = super.read();
            if (value >= 0) left--;
            return value;
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            if (left <= 0) return -1;
            int got = super.read(into, offset, (int) Math.min(length, left));
            if (got > 0) left -= got;
            return got;
        }
    }

    private static String header(WebResourceRequest request, String wanted) {
        for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
            if (wanted.equalsIgnoreCase(header.getKey())) return header.getValue();
        }
        return null;
    }

    static boolean isPicture(String name) {
        return mimeType(name.toLowerCase()).startsWith("image/");
    }

    /** Something the alarm clock can play or show. */
    static boolean isMedia(String name) {
        String type = mimeType(name.toLowerCase());
        return type.startsWith("audio/") || type.startsWith("video/") || isPicture(name);
    }

    /**
     * For the newspaper page: market data (Yahoo), headlines (Google News), the
     * Orthodox calendar (azbyka.ru) and the jokes of the day (anekdot.ru). Only
     * these addresses, built here from checked parts: the page cannot make the
     * app fetch anything else.
     */
    private WebResourceResponse feed(Uri url) {
        String address, type;
        if ("/feed/yahoo".equals(url.getPath())) {
            String symbol = url.getQueryParameter("symbol");
            if (symbol == null || !symbol.matches("[A-Za-z0-9^=.\\-]{1,12}")) return failure(400, "Bad Request");
            address = "https://query1.finance.yahoo.com/v8/finance/chart/" + Uri.encode(symbol) + "?range=3mo&interval=1d";
            type = "application/json";
        } else if ("/feed/news".equals(url.getPath())) {
            String lang = url.getQueryParameter("lang"), country = url.getQueryParameter("country");
            String city = url.getQueryParameter("city");
            if (lang == null || !lang.matches("[a-z]{2}") || country == null || !country.matches("[A-Z]{2}")) {
                return failure(400, "Bad Request");
            }
            address = "https://news.google.com/rss"
                    + (city == null || city.isEmpty() ? "" : "/headlines/section/geo/" + Uri.encode(city))
                    + "?hl=" + lang + "-" + country + "&gl=" + country + "&ceid=" + country + ":" + lang;
            type = "text/xml";
        } else if ("/feed/church".equals(url.getPath())) {
            String date = url.getQueryParameter("date");
            if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return failure(400, "Bad Request");
            address = "https://azbyka.ru/days/api/day/" + date + ".json";
            type = "application/json";
        } else if ("/feed/jokes".equals(url.getPath())) {
            address = "https://www.anekdot.ru/rss/export_j.xml";
            type = "text/xml";
        } else {
            return failure(404, "Not Found");
        }
        try {
            HttpURLConnection link = (HttpURLConnection) new URL(address).openConnection();
            link.setConnectTimeout(10000);
            link.setReadTimeout(15000);
            link.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            if (link.getResponseCode() != 200) {
                link.disconnect();
                return failure(502, "Bad Gateway");
            }
            return new WebResourceResponse(type, "utf-8", 200, "OK",
                    Collections.singletonMap("Cache-Control", "no-store"), link.getInputStream());
        } catch (IOException | RuntimeException unreachable) {
            return failure(502, "Bad Gateway");
        }
    }

    static WebResourceResponse failure(int status, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", status, reason,
                Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    static String mimeType(String file) {
        return Mime.of(file);
    }
}
