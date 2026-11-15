package com.xtr3.downloader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback resolver for public TikTok posts the bundled engine cannot read — mostly the
 * newer "micro drama" / short-drama entry points, which have no yt-dlp extractor.
 *
 * <p>It speaks the same public form flow a browser uses on ssstik.io: load the shell,
 * carry its per-page token, POST one public post URL, then read the download anchor out
 * of the returned HTML fragment. The real media URL is usually base64-encoded into the
 * wrapper path rather than served as a redirect, and because '/' is part of the base64
 * alphabet a long signed payload spans several path segments.
 *
 * <p>Deliberate limits: no captcha solving, no Cloudflare challenge defeat, no login, no
 * cookies from the user's browser or TikTok session, no DRM bypass, no telemetry. A bot
 * challenge is an honest stop. Only public post URLs are sent, and only when the user
 * leaves the fallback enabled.
 */
public final class SsstikResolver {
    public static final String ORIGIN = "https://ssstik.io";
    public static final String HOME = ORIGIN + "/en";
    private static final String FORM = ORIGIN + "/abc?url=dl";
    private static final int MAX_HTML = 1024 * 1024;
    private static final int MAX_ATTEMPTS = 2;
    private static final long MIN_INTERVAL_MS = 1500;
    private static final long COOLDOWN_MS = 12_000;

    /** Both spellings occur: a {@code var s_tt = '...'} global and an {@code include-vals="tt:'...'"} attribute. */
    private static final Pattern TOKEN = Pattern.compile("(?:\\bs_tt\\s*=\\s*|\\btt\\s*:\\s*)['\"]([\\w-]{4,128})['\"]");
    private static final Pattern ANCHOR = Pattern.compile("<a\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern BASE64_SEGMENT = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");
    private static final int MIN_DECODE_SEGMENT = 20;

    /** Hosts whose path may embed a base64-encoded target URL. */
    private static final String[] WRAPPER_HOSTS = {"ssscdn.io", "tikcdn.io", "ssstik.io"};

    /** Wrapper hosts plus the regional TikTok/ByteDance CDNs a decoded URL may legitimately use. */
    private static final String[] MEDIA_HOSTS = {
            "ssstik.io", "ssscdn.io", "tikcdn.io", "tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com",
            "tiktokcdn-eu.com", "tiktokcdnv.com", "bytecdn.com", "byteicdn.com", "bytefcdn.net",
            "byteoversea.com", "ibytedtos.com", "ibyteimg.com", "muscdn.com", "mzstatic.com"};

    /** Every backend verdict the public bundle listens for; the paid HD tier is intentionally ignored. */
    private static final Set<String> SIGNALS = new LinkedHashSet<>(java.util.Arrays.asList(
            "ssssuccess", "ssssuccess_video", "ssssuccess_videoandmp3", "ssssuccess_slides",
            "ssssuccess_music", "ssssuccess_wmonly", "ssssuccess_scraptik", "sssinvalidlink",
            "sssterror", "ssscurlerror", "sssblockedclient", "ssstokenfail", "sssfailure", "ssslimitexceed"));

    private static final Object PACE_LOCK = new Object();
    private static long lastPost;

    public static final class Result {
        public final String url;
        public final String title;
        public final boolean decoded;
        Result(String url, String title, boolean decoded) {
            this.url = url;
            this.title = title;
            this.decoded = decoded;
        }
    }

    private SsstikResolver() { }

    // ---------------------------------------------------------------- parsing

    /** A missing token means the page changed or served a bot challenge; never invent one. */
    public static String extractPageToken(String html) {
        if (html == null) return null;
        Matcher m = TOKEN.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** Reads a verdict from an HX-Trigger header, which may be a bare name or a JSON object. */
    public static String parseSignal(String header) {
        if (header == null) return null;
        String raw = header.trim();
        if (raw.isEmpty()) return null;
        for (String name : SIGNALS) {
            if (raw.equals(name) || raw.contains("\"" + name + "\"")
                    || raw.matches("(?s).*\\b" + Pattern.quote(name) + "\\b.*")) return name;
        }
        return null;
    }

    /** Maps a backend verdict onto the app's localizable error categories. */
    public static String signalCategory(String signal) {
        if (signal == null) return "generic";
        switch (signal) {
            case "sssinvalidlink": return "unsupported";
            case "sssterror": return "unavailable";
            case "ssscurlerror": return "network";
            case "sssblockedclient": return "forbidden";
            case "ssslimitexceed": return "rate";
            case "ssstokenfail": return "network";
            case "ssssuccess_slides": return "slides";
            case "ssssuccess_music": return "audio";
            case "ssssuccess_wmonly": return "watermark";
            default: return "generic";
        }
    }

    /** The watermark-free MP4 anchor, falling back to any non-music download anchor. */
    public static String parseVideoLink(String html) {
        if (html == null) return null;
        String fallback = null;
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            Map<String, String> attrs = LinkParser.attributes(m.group(1));
            String classes = attrs.get("class");
            String href = attrs.get("href");
            if (classes == null || href == null || href.trim().isEmpty()) continue;
            String normalized = " " + classes.toLowerCase(Locale.ROOT) + " ";
            if (!normalized.contains(" download_link ") && !normalized.contains(" without_watermark ")) continue;
            if (normalized.contains(" music ")) continue;
            String resolved = LinkParser.resolve(ORIGIN + "/", LinkParser.htmlEntities(href));
            if (resolved == null) continue;
            if (normalized.contains(" without_watermark ")) return resolved;
            if (fallback == null) fallback = resolved;
        }
        return fallback;
    }

    public static String parseTitle(String html) {
        if (html == null) return "";
        Matcher m = HEADING.matcher(html);
        if (!m.find()) return "";
        return LinkParser.cleanTitle(LinkParser.htmlEntities(TAGS.matcher(m.group(1)).replaceAll("")).trim());
    }

    /**
     * Decodes one strict-base64 path segment into an HTTPS URL, or null when it is not
     * base64, not valid UTF-8 text, or not an HTTPS URL. Binary segments (thumbnail
     * hashes, ids) must return null rather than a mangled string.
     */
    public static String decodeBase64UrlSegment(String segment) {
        if (segment == null) return null;
        String candidate = segment.trim();
        if (candidate.length() < MIN_DECODE_SEGMENT || !BASE64_SEGMENT.matcher(candidate).matches()) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length == 0) return null;
        String text;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f) return null;
        }
        try {
            URI uri = new URI(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()) return null;
            if (uri.getRawUserInfo() != null) return null;
            return uri.toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Unwraps a result link. {@code /} is in the base64 alphabet, so a long signed payload
     * is split across several path segments; every span is rejoined and tried, starting
     * with the tail and growing backwards until one decodes to an HTTPS URL. Opaque proxy
     * paths are returned unchanged so the caller can fetch and follow them instead.
     */
    public static Result unwrapMediaUrl(String href, String title) {
        String host = LinkParser.host(href);
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        if (!hostMatches(host, WRAPPER_HOSTS)) return new Result(href, title, false);
        String path;
        try {
            String raw = new URI(href).getRawPath();
            path = raw == null ? "" : raw;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return new Result(href, title, false);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) segments.add(segment);
        }
        for (int span = Math.max(0, segments.size() - 1); span >= 0; span--) {
            StringBuilder joined = new StringBuilder();
            for (int i = span; i < segments.size(); i++) {
                if (joined.length() > 0) joined.append('/');
                joined.append(urlDecode(segments.get(i)));
            }
            String decoded = decodeBase64UrlSegment(joined.toString());
            if (decoded != null) return new Result(decoded, title, true);
        }
        return new Result(href, title, false);
    }

    public static boolean isAllowedMediaHost(String hostname) {
        if (hostname == null) return false;
        return hostMatches(hostname.toLowerCase(Locale.ROOT).replaceAll("\\.$", ""), MEDIA_HOSTS);
    }

    private static boolean hostMatches(String host, String[] suffixes) {
        for (String suffix : suffixes) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        return false;
    }

    private static String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (IOException | IllegalArgumentException e) {
            return value;
        }
    }

    // --------------------------------------------------------------- transfer

    /** Runs the public shell + form flow and returns a direct, host-checked media URL. */
    public static Result resolve(String postUrl, BooleanSupplier cancelled) throws IOException {
        String signal = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            check(cancelled);
            String cookie = null;
            String shell = null;
            HttpURLConnection home = open(HOME, false, null);
            try {
                home.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                int code = home.getResponseCode();
                if (code != 200) throw new IOException(categoryForStatus(code));
                cookie = sessionCookie(home);
                shell = read(home, MAX_HTML);
            } finally { home.disconnect(); }
            check(cancelled);
            String token = extractPageToken(shell);
            if (token == null) {
                Diagnostics.log("TIKTOK", "SSSTik shell had no public form token (page changed or bot challenge); stopping.");
                throw new IOException("challenge");
            }
            pace(cancelled);
            HttpURLConnection post = open(FORM, true, cookie);
            String body;
            try {
                post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                post.setRequestProperty("Accept", "text/html");
                post.setRequestProperty("Origin", ORIGIN);
                post.setRequestProperty("Referer", HOME);
                // HTMX sets these on the real form; the backend keys on them.
                post.setRequestProperty("hx-request", "true");
                post.setRequestProperty("hx-target", "target");
                post.setRequestProperty("hx-trigger", "_gcaptcha_pt");
                post.setRequestProperty("hx-current-url", HOME);
                post.setDoOutput(true);
                String form = "id=" + encode(postUrl) + "&locale=en&tt=" + encode(token);
                try (OutputStream out = post.getOutputStream()) {
                    out.write(form.getBytes(StandardCharsets.UTF_8));
                }
                int code = post.getResponseCode();
                signal = parseSignal(firstHeader(post, "HX-Trigger"));
                if (code != 200) throw new IOException(categoryForStatus(code));
                body = read(post, MAX_HTML);
            } finally { post.disconnect(); }
            check(cancelled);
            Diagnostics.log("TIKTOK", "SSSTik verdict: " + (signal == null ? "none" : signal));

            if ("ssstokenfail".equals(signal) && attempt < MAX_ATTEMPTS) continue;
            if ("ssslimitexceed".equals(signal) && attempt < MAX_ATTEMPTS) {
                sleep(COOLDOWN_MS, cancelled);
                continue;
            }
            String href = parseVideoLink(body);
            if (href == null) throw new IOException(signalCategory(signal));
            Result unwrapped = unwrapMediaUrl(href, parseTitle(body));
            if (unwrapped == null || !isAllowedMediaHost(LinkParser.host(unwrapped.url))) {
                Diagnostics.log("TIKTOK", "SSSTik media host refused: " + LinkParser.host(unwrapped == null ? href : unwrapped.url));
                throw new IOException("forbidden");
            }
            Diagnostics.log("TIKTOK", "SSSTik resolved a direct MP4 (path-decoded=" + unwrapped.decoded + ")");
            return unwrapped;
        }
        throw new IOException(signalCategory(signal));
    }

    private static String categoryForStatus(int code) {
        if (code == 403 || code == 401) return "challenge";
        if (code == 429) return "rate";
        return "network";
    }

    private static HttpURLConnection open(String url, boolean post, String cookie) throws IOException {
        HttpURLConnection connection = NetworkStack.open(url, true);
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(25_000);
        connection.setRequestProperty("User-Agent", HttpMediaClient.DESKTOP_USER_AGENT);
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        // Only SSSTik's own session cookie is echoed back; no browser or TikTok cookies are read.
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        if (post) connection.setRequestMethod("POST");
        return connection;
    }

    /** Keeps just the name=value pairs of SSSTik's own Set-Cookie headers. */
    private static String sessionCookie(HttpURLConnection connection) {
        StringBuilder jar = new StringBuilder();
        for (int i = 1; ; i++) {
            String key = connection.getHeaderFieldKey(i);
            String value = connection.getHeaderField(i);
            if (key == null && value == null) break;
            if (key == null || !key.equalsIgnoreCase("Set-Cookie") || value == null) continue;
            String pair = value.split(";", 2)[0].trim();
            if (pair.isEmpty() || pair.indexOf('=') <= 0) continue;
            if (jar.length() > 0) jar.append("; ");
            jar.append(pair);
        }
        return jar.length() == 0 ? null : jar.toString();
    }

    private static String firstHeader(HttpURLConnection connection, String name) {
        String value = connection.getHeaderField(name);
        return value != null ? value : connection.getHeaderField(name.toLowerCase(Locale.ROOT));
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
            long wait = lastPost + MIN_INTERVAL_MS - System.currentTimeMillis();
            if (wait > 0) sleep(wait, cancelled);
            lastPost = System.currentTimeMillis();
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
