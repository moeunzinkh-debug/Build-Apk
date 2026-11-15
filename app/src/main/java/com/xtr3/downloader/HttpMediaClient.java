package com.xtr3.downloader;

import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, redirect-checked HTTP client; shares the browser's non-HttpOnly cookie jar. */
public final class HttpMediaClient {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 Xtr3/1.0";
    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([^\\s;\"']+)");

    public interface CurrentConnection { void set(HttpURLConnection connection); }

    public HttpURLConnection open(String url, String referer, long offset, long length,
                                  CurrentConnection sink) throws IOException {
        return open(url, referer, offset, length, sink, null);
    }

    /**
     * @param replay replay-safe headers captured from the player's own request, or null.
     *               Session-bound CDNs often reject a downloader that does not look like
     *               the player, so the allowlisted context is replayed. Cookies always
     *               come from the shared jar at request time; authorization is never replayed.
     */
    public HttpURLConnection open(String url, String referer, long offset, long length,
                                  CurrentConnection sink, Map<String, String> replay) throws IOException {
        String original = LinkParser.normalize(url);
        for (int attempt = 0; attempt < 2; attempt++) {
            // Attempt 0 replays the player's own context. Attempt 1 keeps the captured
            // referer/origin but swaps inconsistent client hints for desktop Chrome.
            String next = original;
            Map<String, String> context = RequestContext.capture(replay);
            if (attempt == 1) {
                context.remove("user-agent");
                context.keySet().removeIf(key -> key.startsWith("sec-ch-ua"));
            }
            Map<String, String> headers = RequestContext.replay(context, referer);
            String userAgent = headers.get("user-agent");
            headers.put("user-agent", userAgent != null ? userAgent : attempt == 0 ? USER_AGENT : DESKTOP_USER_AGENT);
            String denied = null;
            HttpURLConnection connection = null;
            for (int hop = 0; hop <= 6; hop++) {
                connection = (HttpURLConnection) new URL(next).openConnection();
                if (sink != null) sink.set(connection);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8,km;q=0.7");
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                String cookie = CookieManager.getInstance().getCookie(next);
                if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                if (offset >= 0) {
                    // Media players always issue ranged GETs; some CDNs reject open-ended
                    // non-ranged requests, so mirror that with "bytes=offset-" when no length.
                    String end = length > 0 ? String.valueOf(offset + length - 1) : "";
                    connection.setRequestProperty("Range", "bytes=" + offset + "-" + end);
                }
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    String resolved = LinkParser.resolve(next, location);
                    connection.disconnect();
                    if (sink != null) sink.set(null);
                    if (resolved == null || hop == 6) throw new IOException("Too many redirects or an unsafe redirect.");
                    if (next.startsWith("https:") && resolved.startsWith("http:")) {
                        throw new IOException("The server tried an insecure HTTPS-to-HTTP redirect.");
                    }
                    next = resolved;
                    continue;
                }
                if (code == 401 || code == 403) {
                    denied = "HTTP " + code + ": access denied. Open the page in Xtr3, sign in if needed, and detect the video again.";
                    connection.disconnect();
                    if (sink != null) sink.set(null);
                    break;
                }
                if (code == 404 || code == 410) throw new IOException("HTTP " + code + ": this video link no longer exists or has expired.");
                if (code != 200 && code != 206) throw new IOException("The media server returned HTTP " + code + ".");
                if (code == 206) {
                    String range = connection.getHeaderField("Content-Range");
                    long expected = Math.max(0, offset);
                    if (range == null || !range.matches("(?i)bytes\\s+" + expected + "-\\d+/(?:\\d+|\\*)")) {
                        throw new IOException("The server returned an unexpected partial response.");
                    }
                } else if (offset > 0) {
                    throw new IOException("The server ignored the requested segment byte range.");
                }
                return connection;
            }
            if (denied == null) throw new IOException("Could not connect to the media server.");
            if (attempt == 1) throw new IOException(denied);
        }
        throw new IOException("Could not connect to the media server.");
    }

    public String readText(HttpURLConnection connection, int limit) throws IOException {
        String type = connection.getContentType();
        Charset charset = StandardCharsets.UTF_8;
        if (type != null) {
            Matcher m = CHARSET.matcher(type);
            if (m.find()) {
                try { charset = Charset.forName(m.group(1)); }
                catch (IllegalArgumentException ignored) { /* Use UTF-8. */ }
            }
        }
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while (out.size() < limit && (n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()))) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), charset);
        }
    }

    public byte[] readKey(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            byte[] key = new byte[17];
            int count = 0, n;
            while (count < key.length && (n = in.read(key, count, key.length - count)) != -1) count += n;
            if (count != 16) throw new IOException("The AES-128 key must contain exactly 16 bytes.");
            byte[] result = new byte[16];
            System.arraycopy(key, 0, result, 0, 16);
            return result;
        }
    }

    public static String mime(HttpURLConnection connection) {
        String value = connection.getContentType();
        if (value == null) return "";
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    public static long length(HttpURLConnection connection) {
        String range = connection.getHeaderField("Content-Range");
        if (range != null && range.matches("(?i)bytes\\s+\\d+-\\d+/\\d+")) {
            try { return Long.parseLong(range.substring(range.lastIndexOf('/') + 1)); }
            catch (NumberFormatException ignored) { }
        }
        return connection.getHeaderFieldLong("Content-Length", -1);
    }

    public static String dispositionName(HttpURLConnection connection) {
        String value = connection.getHeaderField("Content-Disposition");
        if (value == null) return null;
        Matcher m = Pattern.compile("(?i)filename\\*=UTF-8''([^;]+)").matcher(value);
        if (m.find()) {
            try { return java.net.URLDecoder.decode(m.group(1).trim(), "UTF-8"); }
            catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) { }
        }
        m = Pattern.compile("(?i)filename=\"?([^\";]+)\"?").matcher(value);
        return m.find() ? m.group(1).trim() : null;
    }
}
