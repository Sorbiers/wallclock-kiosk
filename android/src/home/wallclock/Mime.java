package home.wallclock;

/** The content type of a file, by its name (in lower case). */
final class Mime {
    private Mime() {
    }

    static String of(String file) {
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
