package com.xtr3.downloader;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Recognizes the public video platforms handled by the bundled yt-dlp engine. */
public final class SupportedSite {
    public static final String BILIBILI = "bilibili";
    public static final String FACEBOOK = "facebook";
    public static final String YOUTUBE = "youtube";
    public static final String TIKTOK = "tiktok";

    private SupportedSite() { }

    public static String identify(String url) {
        String host = host(url);
        String path = path(url);
        if (matches(host, "bilibili.com") || matches(host, "b23.tv") || matches(host, "bilibili.tv")) return BILIBILI;
        if (matches(host, "facebook.com") || matches(host, "fb.watch") || matches(host, "fb.com")) return FACEBOOK;
        if (matches(host, "youtube.com") || matches(host, "youtu.be")
                || matches(host, "youtube-nocookie.com")) return YOUTUBE;
        if (matches(host, "tiktok.com") || matches(host, "tiktokv.com")) return TIKTOK;
        return null;
    }

    /** True when the user pasted a whole drama/playlist page instead of one episode's share link. */
    public static boolean isCollection(String url) {
        if (!TIKTOK.equals(identify(url))) return false;
        String path = path(url);
        return path.contains("/collection/") || path.contains("/series/") || path.contains("/playlist/");
    }

    /** Canonical watch URL for yt-dlp; preserves case-sensitive video ids and never invents credentials. */
    public static String canonical(String url) {
        String site = identify(url);
        if (site == null) return url;
        String host = host(url), path = rawPath(url);
        if (BILIBILI.equals(site) && matches(host, "bilibili.com")) {
            // The international web shell uses /en/video/BV..., but the extractor expects /video/BV....
            String cleaned = path.replaceFirst("^/(?:en|zh-CN|zh-TW)(?=/)", "");
            if (!cleaned.equals(path)) return rebuild(url, cleaned);
        }
        if (YOUTUBE.equals(site)) {
            String id = null;
            if (matches(host, "youtu.be")) id = segment(path, 0);
            else if (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) id = segment(path, 1);
            if (id != null && id.matches("[A-Za-z0-9_-]{11}")) return "https://www.youtube.com/watch?v=" + id;
        }
        return url;
    }

    public static String label(String url) {
        String site = identify(url);
        if (BILIBILI.equals(site)) return "Bilibili";
        if (FACEBOOK.equals(site)) return "Facebook";
        if (YOUTUBE.equals(site)) return "YouTube";
        if (TIKTOK.equals(site)) return "TikTok";
        return "Video";
    }

    private static String rebuild(String url, String path) {
        try {
            URI u = new URI(url);
            return new URI(u.getScheme(), null, u.getHost(), u.getPort(), path, u.getQuery(), null).toASCIIString();
        } catch (URISyntaxException e) { return url; }
    }
    private static String segment(String path, int index) {
        String[] parts = path.split("/");
        return index + 1 < parts.length ? parts[index + 1] : null;
    }
    private static String host(String url) {
        String h = LinkParser.host(url);
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }
    private static String path(String url) { return rawPath(url).toLowerCase(Locale.ROOT); }
    private static String rawPath(String url) {
        try {
            String p = new URI(url).getPath();
            return p == null ? "" : p;
        } catch (URISyntaxException | IllegalArgumentException e) { return ""; }
    }
    private static boolean matches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
