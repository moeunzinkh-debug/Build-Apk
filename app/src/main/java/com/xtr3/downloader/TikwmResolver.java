package com.xtr3.downloader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Fallback resolver for public TikTok and TikTok micro-drama posts via TikWM's public JSON
 * endpoint ({@code https://www.tikwm.com/api/}).
 *
 * <p>When {@code hd=1} is requested, TikWM reports distinct {@code hdplay} (HD, no watermark),
 * {@code play} (standard, no watermark), and {@code wmplay} (watermarked) MP4 links with byte
 * sizes when available, plus {@code images} for photo carousels. Relative {@code /video/media/...}
 * paths are resolved against {@code https://www.tikwm.com/} and checked against an HTTPS host
 * allow-list before any candidate is offered.
 *
 * <p>Deliberate limits: no login, no cookies from the user's browser or TikTok session, no
 * captcha solving, no Cloudflare challenge bypass, and no DRM bypass. Only the single public
 * post URL pasted by the user is queried, and the resolver can be turned off in Developer options.
 */
public final class TikwmResolver {
    public static final String ORIGIN = "https://www.tikwm.com";
    public static final String API = ORIGIN + "/api/";
    private static final int MAX_JSON = 512 * 1024;
    private static final int MAX_ATTEMPTS = 2;
    private static final long MIN_INTERVAL_MS = 1500;
    private static final long COOLDOWN_MS = 2200;

    /** TikWM origin plus regional TikTok/ByteDance CDNs a returned video link may legitimately use. */
    private static final String[] MEDIA_HOSTS = {
            "tikwm.com", "tikcdn.io", "ssscdn.io", "tiktok.com", "tiktokcdn.com",
            "tiktokcdn-us.com", "tiktokcdn-eu.com", "tiktokcdnv.com", "tiktokv.com",
            "bytecdn.com", "byteicdn.com", "bytefcdn.net", "byteoversea.com",
            "ibytedtos.com", "ibyteimg.com", "muscdn.com", "mzstatic.com"};

    private static final Object PACE_LOCK = new Object();
    private static long lastRequest;

    public static final class Result {
        public final String url;
        public final String title;
        public final long size;
        public final List<MediaCandidate.Quality> qualities;

        Result(String url, String title, long size, List<MediaCandidate.Quality> qualities) {
            this.url = url;
            this.title = title;
            this.size = size;
            this.qualities = qualities == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(qualities));
        }
    }

    private TikwmResolver() { }

    // ---------------------------------------------------------------- parsing

    /** Parses one TikWM JSON payload into validated HTTPS MP4 options, or throws a category code. */
    public static Result parseResponse(String rawJson) throws IOException {
        if (rawJson == null) throw new IOException("generic");
        String trimmed = rawJson.replaceFirst("^[\\s\\uFEFF]+", "").trim();
        if (trimmed.isEmpty()) throw new IOException("generic");
        if (trimmed.startsWith("<")) throw new IOException("challenge");

        JSONObject root;
        try {
            root = new JSONObject(trimmed);
        } catch (JSONException e) {
            throw new IOException("generic");
        }

        int code = root.optInt("code", -1);
        if (code != 0) {
            throw new IOException(messageCategory(optCleanString(root, "msg")));
        }

        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new IOException("unavailable");

        JSONArray images = data.optJSONArray("images");
        if (images != null && images.length() > 0) {
            throw new IOException("slides");
        }

        Map<String, MediaCandidate.Quality> byUrl = new LinkedHashMap<>();
        boolean[] flags = new boolean[2]; // [0] = sawForbiddenHost, [1] = sawAudioOnly
        addStream(data, "hdplay", "hd_size", "hdplay", "HD (no watermark) · MP4", "HD", 1080, byUrl, flags);
        addStream(data, "play", "size", "play", "Standard (no watermark) · MP4", "Standard", 720, byUrl, flags);
        addStream(data, "wmplay", "wm_size", "wmplay", "Watermarked · MP4", "Watermarked", 0, byUrl, flags);

        if (byUrl.isEmpty()) {
            if (flags[0]) throw new IOException("forbidden");
            if (flags[1] || optCleanString(data, "music") != null) throw new IOException("audio");
            throw new IOException("unavailable");
        }

        ArrayList<MediaCandidate.Quality> qualities = new ArrayList<>(byUrl.values());
        MediaCandidate.Quality primary = qualities.get(0);
        String rawTitle = optCleanString(data, "title");
        String title = rawTitle == null ? "" : LinkParser.cleanTitle(LinkParser.htmlEntities(rawTitle));
        return new Result(primary.url, title, primary.size, qualities);
    }

    private static void addStream(JSONObject data, String urlKey, String sizeKey,
                                  String id, String label, String quality, int heightHint,
                                  Map<String, MediaCandidate.Quality> byUrl, boolean[] flags) {
        String raw = optCleanString(data, urlKey);
        if (raw == null) return;
        String resolved = resolveHttpsUrl(raw);
        if (resolved == null) return;
        if (!isAllowedMediaHost(LinkParser.host(resolved))) {
            flags[0] = true;
            return;
        }
        if (isAudioOnlyPath(resolved)) {
            flags[1] = true;
            return;
        }
        long bytes = positiveSize(data, sizeKey);
        MediaCandidate.Quality existing = byUrl.get(resolved);
        if (existing != null) {
            if (existing.size <= 0 && bytes > 0) existing.size = bytes;
            return;
        }
        MediaCandidate.Quality option = new MediaCandidate.Quality(
                id, label, quality, "mp4", bytes, heightHint, true);
        option.url = resolved;
        byUrl.put(resolved, option);
    }

    /** Resolves relative TikWM paths or absolute URLs into an HTTPS URI with no userinfo. */
    public static String resolveHttpsUrl(String raw) {
        String clean = optTrimmed(raw);
        if (clean == null) return null;
        String candidate = LinkParser.resolve(ORIGIN + "/", clean);
        if (candidate == null) return null;
        try {
            URI uri = new URI(candidate);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()) {
                return null;
            }
            if (uri.getRawUserInfo() != null) return null;
            return uri.toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Maps TikWM's free-text {@code msg} field onto Xtr3's stable localizable error categories. */
    public static String messageCategory(String message) {
        if (message == null || message.trim().isEmpty()) return "generic";
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("limit") || m.contains("too many") || m.contains("fast")
                || m.contains("busy") || m.contains("1 request") || m.contains("frequency")) {
            return "rate";
        }
        if (m.contains("private") || m.contains("login") || m.contains("permission") || m.contains("authorize")) {
            return "login";
        }
        if (m.contains("paid") || m.contains("purchase") || m.contains("subscribe") || m.contains("vip")) {
            return "paid";
        }
        if (m.contains("live")) {
            return "live";
        }
        if (m.contains("parsing") || m.contains("invalid") || m.contains("check url")
                || m.contains("not support") || m.contains("unsupported")) {
            return "unsupported";
        }
        if (m.contains("not found") || m.contains("deleted") || m.contains("removed")
                || m.contains("unavailable") || m.contains("not exist") || m.contains("status")) {
            return "unavailable";
        }
        return "generic";
    }

    public static boolean isAllowedMediaHost(String hostname) {
        if (hostname == null) return false;
        return hostMatches(hostname.toLowerCase(Locale.ROOT).replaceAll("\\.$", ""), MEDIA_HOSTS);
    }

    public static boolean isTikwmHost(String hostname) {
        if (hostname == null) return false;
        String host = hostname.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        return host.equals("tikwm.com") || host.endsWith(".tikwm.com");
    }

    private static boolean hostMatches(String host, String[] suffixes) {
        for (String suffix : suffixes) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        return false;
    }

    private static boolean isAudioOnlyPath(String url) {
        String p = LinkParser.path(url);
        return p.endsWith(".mp3") || p.endsWith(".m4a") || p.endsWith(".aac")
                || p.endsWith(".ogg") || p.endsWith(".wav") || p.endsWith(".flac");
    }

    private static String optCleanString(JSONObject obj, String key) {
        if (obj == null || key == null || obj.isNull(key)) return null;
        return optTrimmed(obj.optString(key, ""));
    }

    private static String optTrimmed(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? null : value;
    }

    private static long positiveSize(JSONObject obj, String key) {
        if (obj == null || key == null || obj.isNull(key)) return -1;
        long value = obj.optLong(key, -1);
        return value > 0 ? value : -1;
    }

    // --------------------------------------------------------------- transfer

    /** Queries TikWM for one public TikTok post URL, respecting the 1 req/s pacing limit. */
    public static Result resolve(String postUrl, BooleanSupplier cancelled) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            check(cancelled);
            pace(cancelled);
            String endpoint = API + "?url=" + encode(postUrl) + "&hd=1";
            HttpURLConnection connection = open(endpoint);
            String body;
            try {
                int status = connection.getResponseCode();
                if (status == 429 && attempt < MAX_ATTEMPTS) {
                    Diagnostics.log("TIKTOK", "TikWM returned HTTP 429; cooling down once.");
                    connection.disconnect();
                    connection = null;
                    sleep(COOLDOWN_MS, cancelled);
                    continue;
                }
                if (status != 200) {
                    throw new IOException(categoryForStatus(status));
                }
                body = read(connection, MAX_JSON);
            } finally {
                if (connection != null) connection.disconnect();
            }
            check(cancelled);
            try {
                Result result = parseResponse(body);
                Diagnostics.log("TIKTOK", "TikWM resolved " + result.qualities.size() + " MP4 option(s)");
                return result;
            } catch (IOException e) {
                last = e;
                if ("rate".equals(e.getMessage()) && attempt < MAX_ATTEMPTS) {
                    Diagnostics.log("TIKTOK", "TikWM rate limit reported; retrying once after cooldown.");
                    sleep(COOLDOWN_MS, cancelled);
                    continue;
                }
                throw e;
            }
        }
        throw last == null ? new IOException("generic") : last;
    }

    private static String categoryForStatus(int code) {
        if (code == 401 || code == 403) return "challenge";
        if (code == 429) return "rate";
        return "network";
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = NetworkStack.open(url, true);
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(25_000);
        connection.setRequestProperty("User-Agent", HttpMediaClient.DESKTOP_USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        connection.setRequestProperty("Referer", ORIGIN + "/");
        return connection;
    }

    private static String read(HttpURLConnection connection, int limit) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
                if (buffer.size() > limit) throw new IOException("The resolver response was too large.");
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String encode(String value) throws IOException {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IOException("Encoding is unavailable.");
        }
    }

    private static void pace(BooleanSupplier cancelled) throws IOException {
        synchronized (PACE_LOCK) {
            long wait = lastRequest + MIN_INTERVAL_MS - System.currentTimeMillis();
            if (wait > 0) sleep(wait, cancelled);
            lastRequest = System.currentTimeMillis();
        }
    }

    private static void sleep(long millis, BooleanSupplier cancelled) throws IOException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            check(cancelled);
            try {
                Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Cancelled.");
            }
        }
    }

    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled.");
    }
}
