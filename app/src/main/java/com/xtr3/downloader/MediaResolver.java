package com.xtr3.downloader;

import android.content.Context;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.List;

/** Finds ordinary public media links. It does not defeat authentication or DRM. */
public final class MediaResolver {
    public static final class Result {
        public final List<MediaCandidate> candidates;
        public final boolean needsBrowser;
        Result(List<MediaCandidate> candidates, boolean needsBrowser) {
            this.candidates = candidates;
            this.needsBrowser = needsBrowser;
        }
    }
    private final Context context;
    private final HttpMediaClient client = new HttpMediaClient();
    private volatile boolean cancelled;
    private volatile HttpURLConnection current;
    private volatile PlatformEngine platform;

    public MediaResolver(Context context) { this.context = context.getApplicationContext(); }

    public void cancel() {
        cancelled = true;
        HttpURLConnection c = current;
        if (c != null) c.disconnect();
        PlatformEngine p = platform;
        if (p != null) p.cancel();
    }

    public Result resolve(String input) throws IOException {
        String url = LinkParser.normalize(input);
        if (SupportedSite.isCollection(url)) throw new IOException("COLLECTION");
        String site = SupportedSite.identify(url);
        if (site != null) {
            String target = SupportedSite.needsExpansion(url) ? expandShortLink(url) : url;
            // The short-drama entry point has no engine extractor; do not waste a round trip on it.
            if (SupportedSite.isShortDrama(target)) {
                return new Result(Collections.singletonList(viaFallback(target, null)), false);
            }
            platform = new PlatformEngine(context);
            try {
                return new Result(Collections.singletonList(platform.inspect(target, () -> cancelled)), false);
            } catch (IOException e) {
                if (!SupportedSite.TIKTOK.equals(site) || !fallbackEnabled() || isCancelled(e)) throw e;
                Diagnostics.log("TIKTOK", "Bundled engine could not read this TikTok link; trying the SSSTik fallback.");
                return new Result(Collections.singletonList(viaFallback(target, e)), false);
            } finally { platform = null; }
        }
        HttpURLConnection connection = client.open(url, null, -1, -1, c -> current = c);
        try {
            check();
            String mime = HttpMediaClient.mime(connection);
            if (LinkParser.isHls(connection.getURL().toString(), mime)) {
                return new Result(Collections.singletonList(hls(connection, url)), false);
            }
            boolean html = mime.contains("html") || mime.startsWith("text/") || mime.contains("json");
            if (LinkParser.isVideo(url, mime) && !html) {
                MediaCandidate c = new MediaCandidate(connection.getURL().toString(), url,
                        name(connection, url), MediaCandidate.DIRECT);
                c.mime = mime;
                c.size = HttpMediaClient.length(connection);
                return new Result(Collections.singletonList(c), false);
            }
            if (!html && !mime.isEmpty() && !mime.equals("application/octet-stream")) {
                throw new IOException("This URL returns " + mime + ", not a video or web page.");
            }
            String text = client.readText(connection, 2 * 1024 * 1024);
            check();
            String trimmed = text.replaceFirst("^[\\s\\uFEFF]+", "");
            if (trimmed.startsWith("#EXTM3U")) {
                return new Result(Collections.singletonList(hlsText(text, connection.getURL().toString(), url)), false);
            }
            List<MediaCandidate> candidates = LinkParser.extract(text, connection.getURL().toString());
            for (MediaCandidate c : candidates) c.pageUrl = url;
            return new Result(candidates, candidates.isEmpty());
        } finally {
            connection.disconnect();
            current = null;
        }
    }

    private boolean fallbackEnabled() {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("ssstikFallback", true);
    }

    private static boolean isCancelled(IOException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("cancel");
    }

    /**
     * Resolves one public TikTok post through the SSSTik form flow when the bundled engine
     * cannot read it. The returned candidate is an ordinary direct MP4 on a TikTok CDN.
     */
    private MediaCandidate viaFallback(String url, IOException cause) throws IOException {
        if (!fallbackEnabled()) {
            if (cause != null) throw cause;
            throw new IOException("unsupported");
        }
        SsstikResolver.Result resolved;
        try {
            resolved = SsstikResolver.resolve(url, () -> cancelled);
        } catch (IOException e) {
            // Keep the engine's more specific diagnosis when the fallback learned nothing new.
            if (cause != null && ("generic".equals(e.getMessage()) || "network".equals(e.getMessage()))) throw cause;
            throw e;
        }
        String title = resolved.title == null || resolved.title.isEmpty() ? "TikTok video" : resolved.title;
        MediaCandidate candidate = new MediaCandidate(resolved.url, url, title, MediaCandidate.DIRECT);
        candidate.mime = "video/mp4";
        candidate.site = SupportedSite.TIKTOK;
        candidate.platformNote = "SSSTik";
        return candidate;
    }

    /** Public share shorteners redirect to the real watch page; keep the original URL if expansion fails. */
    private String expandShortLink(String url) {
        if (!SupportedSite.needsExpansion(url)) return url;
        HttpURLConnection connection = null;
        try {
            connection = client.open(url, null, -1, -1, c -> current = c);
            check();
            String next = LinkParser.normalize(connection.getURL().toString());
            // A bot check or login interstitial is not the video; keep the original share link.
            if (SupportedSite.identify(next) == null || SupportedSite.isInterstitial(next)) return url;
            return next;
        } catch (IOException | RuntimeException e) {
            return url;
        } finally {
            if (connection != null) connection.disconnect();
            current = null;
        }
    }

    private MediaCandidate hls(HttpURLConnection connection, String page) throws IOException {
        return hlsText(client.readText(connection, 2 * 1024 * 1024), connection.getURL().toString(), page);
    }

    private MediaCandidate hlsText(String text, String actualUrl, String page) throws IOException {
        String url = actualUrl;
        String quality = "Best available";
        for (int depth = 0; depth < 3; depth++) {
            HlsPlaylist playlist = HlsPlaylist.parse(text, url);
            if (!playlist.master) {
                MediaCandidate c = new MediaCandidate(url, page, "Video", MediaCandidate.HLS);
                c.mime = "application/vnd.apple.mpegurl";
                c.quality = quality;
                return c;
            }
            HlsPlaylist.Variant best = HlsPlaylist.bestVariant(playlist);
            url = best.url;
            quality = best.label();
            check();
            HttpURLConnection next = client.open(url, page, -1, -1, c -> current = c);
            try { text = client.readText(next, 2 * 1024 * 1024); url = next.getURL().toString(); }
            finally { next.disconnect(); current = null; }
        }
        throw new IOException("This playlist has too many nested master playlists.");
    }

    private String name(HttpURLConnection connection, String page) {
        String name = HttpMediaClient.dispositionName(connection);
        if (name != null && !name.trim().isEmpty()) return LinkParser.cleanTitle(name);
        String path = LinkParser.path(connection.getURL().toString());
        if (!path.isEmpty() && path.contains(".")) {
            String base = path.substring(path.lastIndexOf('/') + 1);
            if (base.contains(".")) return LinkParser.cleanTitle(base.substring(0, base.lastIndexOf('.')));
        }
        return "Video_" + LinkParser.host(page).replace('.', '_');
    }

    private void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled.");
    }
}
