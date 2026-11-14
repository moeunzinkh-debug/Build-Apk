package com.xtr3.downloader;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Framework-independent parsing, also exercised by the JVM unit tests. */
public final class LinkParser {
    private static final Pattern TAG = Pattern.compile(
            "<(video|source|meta|a|iframe)\\b([^>]*>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final Pattern LIST_ATTRIBUTE = Pattern.compile(
            "([a-zA-Z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,]*))");
    private static final Pattern JSON_URL = Pattern.compile(
            "[\"'](?:video_?url|videoUrl|content_?[uU]rl|contentURL|file|src|source|hls|hls_?url|playlist|stream_?url|download_?url|mp4|m3u8)[\"']\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)')",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_URL = Pattern.compile(
            "(?:https?:)?\\\\?/\\\\?/[^\\s\"'<>\\\\]+\\.(?:mp4|m3u8|webm|m4v|mov|ts)(?:[?][^\\s\"'<>\\\\]*)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MEDIA_KEY = Pattern.compile(
            "(?:video|content|file|src|source|hls|playlist|stream|download|mp4|m3u8)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HLS_ANYWHERE = Pattern.compile("(?i)\\.m3u8(?:[?&#]|$)");
    private static final Pattern MEDIA_EXT_ANYWHERE = Pattern.compile("(?i)\\.(mp4|m3u8|webm|m4v|mov|ogv|mkv)(?:[?&#]|$)");
    private static final Pattern MEDIA_HINT = Pattern.compile(
            "(?i)(?:videoplayback|/hls/|/video/|/stream/|[?&](?:mime|type|format)=video|[?&](?:file|url|src)=[^&]*\\.(?:mp4|m3u8))");

    private LinkParser() { }

    public static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException("Enter a video link first.");
        String value = input.trim();
        if (value.length() > 8192) throw new IllegalArgumentException("This link is too long.");
        // Accept a URL shared with a short description, but never arbitrary prose.
        if (value.startsWith("www.") || value.startsWith("xstree.com/")) value = "https://" + value;
        URI uri = uri(value);
        if (!isWeb(uri) || uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("Use a complete http:// or https:// video link.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Links containing a username or password are not supported.");
        }
        return uri.toASCIIString();
    }

    public static String resolve(String base, String value) {
        if (value == null) return null;
        String clean = htmlEntities(jsonUnescape(value.trim())).replace(" ", "%20");
        if (clean.isEmpty() || clean.length() > 8192 || clean.startsWith("#")) return null;
        try {
            URI uri = base == null ? new URI(clean) : uri(base).resolve(new URI(clean));
            return isWeb(uri) && uri.getHost() != null && uri.getRawUserInfo() == null
                    ? uri.toASCIIString() : null;
        } catch (IllegalArgumentException | URISyntaxException e) {
            return null;
        }
    }

    private static URI uri(String value) {
        try {
            return new URI(value.contains(" ") ? value.replace(" ", "%20") : value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("This link is not valid.", e);
        }
    }

    private static boolean isWeb(URI uri) {
        String scheme = uri.getScheme();
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    public static boolean isHls(String url, String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return path(url).endsWith(".m3u8") || (url != null && HLS_ANYWHERE.matcher(url).find())
                || m.contains("mpegurl") || m.contains("vnd.apple.mpegurl");
    }

    public static boolean isVideo(String url, String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String p = path(url);
        return isHls(url, mime) || p.endsWith(".mp4") || p.endsWith(".webm")
                || p.endsWith(".m4v") || p.endsWith(".mov") || p.endsWith(".ogv")
                || p.endsWith(".mkv") || p.endsWith(".ts")
                || (m.startsWith("video/") && !m.contains("mpegurl"));
    }

    /** Broader browser-side heuristic for players that hide the extension in a query string. */
    public static boolean isProbablyMedia(String url, String mime) {
        return isVideo(url, mime) || hasMediaHint(url);
    }

    public static boolean hasMediaHint(String url) {
        return url != null && (MEDIA_EXT_ANYWHERE.matcher(url).find() || MEDIA_HINT.matcher(url).find());
    }

    public static boolean isFragment(String url) {
        String p = path(url);
        return p.endsWith(".ts") || p.endsWith(".m4s") || p.endsWith(".aac");
    }

    public static String path(String url) {
        try {
            String p = uri(url).getPath();
            return p == null ? "" : p.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) { return ""; }
    }

    public static String host(String url) {
        try { return uri(url).getHost(); } catch (IllegalArgumentException e) { return ""; }
    }

    public static String extension(String url, String mime) {
        String p = path(url), m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("webm") || p.endsWith(".webm")) return ".webm";
        if (m.contains("quicktime") || p.endsWith(".mov")) return ".mov";
        if (m.contains("matroska") || p.endsWith(".mkv")) return ".mkv";
        if (m.contains("mp2t") || p.endsWith(".ts")) return ".ts";
        if (p.endsWith(".m4v")) return ".m4v";
        if (p.endsWith(".ogv")) return ".ogv";
        return ".mp4";
    }

    public static String pageTitle(String html) {
        Matcher m = TITLE.matcher(html);
        return m.find() ? cleanTitle(htmlEntities(m.group(1))) : "Video";
    }

    public static String cleanTitle(String value) {
        if (value == null) return "Video";
        String title = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (title.length() > 120) title = title.substring(0, 120).trim();
        return title.isEmpty() ? "Video" : title;
    }

    public static String safeFilename(String value) {
        String name = cleanTitle(value).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\.{2,}", "_").trim();
        if (name.length() > 80) name = name.substring(0, 80);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "Video";
        return name;
    }

    public static List<MediaCandidate> extract(String html, String pageUrl) {
        Map<String, MediaCandidate> found = new LinkedHashMap<>();
        String title = pageTitle(html);
        Matcher tags = TAG.matcher(html);
        while (tags.find() && found.size() < 24) {
            String tag = tags.group(1).toLowerCase(Locale.ROOT);
            Map<String, String> attrs = attributes(tags.group(2));
            List<String> values = new ArrayList<>();
            if (tag.equals("meta")) {
                String name = attrs.getOrDefault("property", attrs.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
                if (name.startsWith("og:video") || name.equals("twitter:player:stream") || name.equals("video_url")) {
                    values.add(attrs.get("content"));
                }
            } else {
                values.add(attrs.get("src"));
                values.add(attrs.get("data-src"));
                values.add(attrs.get("data-video"));
                values.add(attrs.get("data-file"));
                values.add(attrs.get("data-url"));
                if (tag.equals("a")) {
                    values.add(attrs.get("href"));
                    if (attrs.containsKey("download")) values.add(attrs.get("data-src"));
                }
            }
            for (String value : values) {
                String url = resolve(pageUrl, value);
                if (url != null && (isVideo(url, attrs.get("type")) || tag.equals("video")
                        || (tag.equals("meta") && value != null))) {
                    add(found, url, pageUrl, title, attrs.get("type"), label(attrs));
                }
            }
        }
        Matcher json = JSON_URL.matcher(html);
        while (json.find() && found.size() < 24) {
            String value = json.group(1) != null ? json.group(1) : json.group(2);
            String url = resolve(pageUrl, value);
            if (url != null && isVideo(url, null)) add(found, url, pageUrl, title, null, "");
        }
        Matcher bare = BARE_URL.matcher(html);
        while (bare.find() && found.size() < 24) {
            String url = resolve(pageUrl, bare.group());
            if (url != null && isVideo(url, null)) add(found, url, pageUrl, title, null, "");
        }
        return new ArrayList<>(found.values());
    }

    private static String label(Map<String, String> attrs) {
        String quality = attrs.get("label");
        if (quality == null) quality = attrs.get("title");
        if (quality == null) quality = attrs.get("res");
        return quality == null ? "" : cleanTitle(htmlEntities(quality));
    }

    private static void add(Map<String, MediaCandidate> found, String url, String page,
                            String title, String mime, String quality) {
        if (found.containsKey(url)) return;
        MediaCandidate c = new MediaCandidate(url, page, title,
                isHls(url, mime) ? MediaCandidate.HLS : MediaCandidate.DIRECT);
        c.mime = mime;
        c.quality = quality;
        found.put(url, c);
    }

    public static Map<String, String> attributes(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher m = ATTRIBUTE.matcher(text);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            values.put(m.group(1).toLowerCase(Locale.ROOT), htmlEntities(value));
        }
        return values;
    }

    /** HLS attribute lists are comma-separated; HTML unquoted attribute values may contain commas. */
    public static Map<String, String> listAttributes(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher m = LIST_ATTRIBUTE.matcher(text);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3).trim();
            values.put(m.group(1).toUpperCase(Locale.ROOT), value);
        }
        return values;
    }

    public static String htmlEntities(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#38;", "&").replace("&#34;", "\"").replace("&#39;", "'")
                .replace("&#x26;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    /** Decode JSON string escapes, including escaped CDN URLs and Unicode surrogate pairs. */
    public static String jsonUnescape(String value) {
        if (value == null || value.indexOf('\\') < 0) return value == null ? "" : value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) { out.append(c); continue; }
            char e = value.charAt(++i);
            switch (e) {
                case '"': case '\'': case '/': case '\\': out.append(e); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 < value.length()) {
                        try {
                            out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ex) { out.append("\\u"); }
                    } else out.append("\\u");
                    break;
                default: out.append('\\').append(e);
            }
        }
        return out.toString();
    }
}
