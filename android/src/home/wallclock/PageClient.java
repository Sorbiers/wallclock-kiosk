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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Answers the page's own requests from inside the app: its files come from the
 * bundled assets, "/cam/..." is fetched from a camera with the login, and
 * "/slides/..." comes from the slideshow folder and "/media/..." from the
 * folder of what the alarm clock plays.
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
        if (path.startsWith("/cam/")) return cameras.snapshot(path);
        if (path.startsWith("/slides/")) return slide(new File(path).getName());   // the name only: no ".."
        if (path.startsWith("/media/")) return media(new File(path).getName(), header(request, "Range"));
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

    private WebResourceResponse slide(String name) {
        try {
            if (slides == null || !isPicture(name)) return failure(404, "Not Found");
            return new WebResourceResponse(mimeType(name.toLowerCase()), null, new FileInputStream(new File(slides, name)));
        } catch (IOException missing) {
            return failure(404, "Not Found");
        }
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

    static WebResourceResponse failure(int status, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", status, reason,
                Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    static String mimeType(String file) {
        if (file.endsWith(".html")) return "text/html";
        if (file.endsWith(".js")) return "text/javascript";
        if (file.endsWith(".css")) return "text/css";
        if (file.endsWith(".svg")) return "image/svg+xml";
        if (file.endsWith(".png")) return "image/png";
        if (file.endsWith(".jpg") || file.endsWith(".jpeg")) return "image/jpeg";
        if (file.endsWith(".webp")) return "image/webp";
        if (file.endsWith(".gif")) return "image/gif";
        if (file.endsWith(".mp3")) return "audio/mpeg";
        if (file.endsWith(".m4a")) return "audio/mp4";
        if (file.endsWith(".aac")) return "audio/aac";
        if (file.endsWith(".ogg")) return "audio/ogg";
        if (file.endsWith(".wav")) return "audio/wav";
        if (file.endsWith(".flac")) return "audio/flac";
        if (file.endsWith(".mp4") || file.endsWith(".m4v")) return "video/mp4";
        if (file.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }
}
