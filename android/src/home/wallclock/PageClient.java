package home.wallclock;

import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;

/**
 * Answers the page's own requests from inside the app: its files come from the
 * bundled assets, "/cam/..." is fetched from a camera with the login, and
 * "/slides/..." comes from the slideshow folder.
 * Anything else (the weather service) goes to the network as usual.
 */
final class PageClient extends WebViewClient {
    private static final String HOST = Uri.parse(MainActivity.PAGE).getHost();

    private final AssetManager assets;
    private final Cameras cameras;
    private final File slides;

    PageClient(AssetManager assets, Cameras cameras, File slides) {
        this.assets = assets;
        this.cameras = cameras;
        this.slides = slides;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (!HOST.equals(url.getHost())) return null;
        String path = url.getPath() == null ? "/" : url.getPath();
        if (path.startsWith("/cam/")) return cameras.snapshot(path);
        if (path.startsWith("/slides/")) return slide(new File(path).getName());   // the name only: no ".."
        String file = path.equals("/") ? "index.html" : path.substring(1);
        try {
            return new WebResourceResponse(mimeType(file), "utf-8", assets.open(file));
        } catch (IOException missing) {
            return failure(404, "Not Found");
        }
    }

    /** The kiosk page never navigates anywhere else. */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return !HOST.equals(request.getUrl().getHost());
    }

    private WebResourceResponse slide(String name) {
        try {
            if (slides == null || !isPicture(name)) return failure(404, "Not Found");
            return new WebResourceResponse(mimeType(name.toLowerCase()), null, new FileInputStream(new File(slides, name)));
        } catch (IOException missing) {
            return failure(404, "Not Found");
        }
    }

    static boolean isPicture(String name) {
        return mimeType(name.toLowerCase()).startsWith("image/");
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
        return "application/octet-stream";
    }
}
