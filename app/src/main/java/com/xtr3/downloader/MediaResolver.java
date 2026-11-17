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
                if (!SupportedSite.TIKTOK.equals(site) || !anyFallbackEnabled() || isCancelled(e)) throw e;
                Diagnostics.log("TIKTOK", "Bundled engine could not read this TikTok link; trying third-party fallback.");
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

    private boolean tikwmEnabled() {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("tikwmFallback", true);
    }

    private boolean ssstikEnabled() {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("ssstikFallback", true);
    }

    private boolean anyFallbackEnabled() {
        return tikwmEnabled() || ssstikEnabled();
    }

    private static boolean isCancelled(IOException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("cancel");
    }

    /**
     * Resolves one public TikTok post through TikWM (JSON + HD/standard qualities) and falls back
     * to SSSTik when the bundled engine cannot read the link.
     */
    private MediaCandidate viaFallback(String url, IOException cause) throws IOException {
        boolean useTikwm = tikwmEnabled();
        boolean useSsstik = ssstikEnabled();
        if (!useTikwm && !useSsstik) {
            if (cause != null) throw cause;
            throw new IOException("unsupported");
        }
        IOException tikwmError = null;
        if (useTikwm) {
            try {
                return fromTikwm(url, TikwmResolver.resolve(url, () -> cancelled));
            } catch (IOException e) {
                if (isCancelled(e)) throw e;
                // A confirmed photo carousel has no single video file in any resolver.
                if ("slides".equals(e.getMessage())) throw e;
                tikwmError = e;
                if (useSsstik) {
                    Diagnostics.log("TIKTOK", "TikWM fallback did not resolve (" + e.getMessage() + "); trying SSSTik.");
                }
            }
        }
        if (useSsstik) {
            try {
                return fromSsstik(url, SsstikResolver.resolve(url, () -> cancelled));
            } catch (IOException e) {
                if (isCancelled(e)) throw e;
                throw chooseFailure(cause, tikwmError, e);
            }
        }
        throw chooseFailure(cause, tikwmError, null);
    }

    private static MediaCandidate fromTikwm(String pageUrl, TikwmResolver.Result resolved) {
        String title = resolved.title == null || resolved.title.isEmpty() ? "TikTok video" : resolved.title;
        MediaCandidate candidate = new MediaCandidate(resolved.url, pageUrl, title, MediaCandidate.DIRECT);
        candidate.mime = "video/mp4";
        candidate.site = SupportedSite.TIKTOK;
        candidate.platformNote = "TikWM";
        candidate.size = resolved.size;
        if (resolved.qualities != null && !resolved.qualities.isEmpty()) {
            candidate.qualities = new java.util.ArrayList<>(resolved.qualities);
            candidate.selectQuality(candidate.qualities.get(0));
        }
        return candidate;
    }

    private static MediaCandidate fromSsstik(String pageUrl, SsstikResolver.Result resolved) {
        String title = resolved.title == null || resolved.title.isEmpty() ? "TikTok video" : resolved.title;
        MediaCandidate candidate = new MediaCandidate(resolved.url, pageUrl, title, MediaCandidate.DIRECT);
        candidate.mime = "video/mp4";
        candidate.site = SupportedSite.TIKTOK;
        candidate.platformNote = "SSSTik";
        return candidate;
    }

    static IOException chooseFailure(IOException engineCause, IOException tikwmError, IOException ssstikError) {
        IOException best = null;
        int bestRank = -1;
        for (IOException candidate : new IOException[]{engineCause, tikwmError, ssstikError}) {
            if (candidate == null) continue;
            int rank = failureRank(candidate.getMessage());
            if (rank >= bestRank) {
                best = candidate;
                bestRank = rank;
            }
        }
        return best != null ? best : new IOException("generic");
    }

    static int failureRank(String category) {
        if (category == null) return 0;
        switch (category) {
            case "slides":
            case "audio":
            case "watermark":
            case "paid":
            case "login":
            case "protected":
            case "live":
                return 5;
            case "unavailable":
                return 4;
            case "rate":
            case "forbidden":
            case "challenge":
                return 3;
            case "unsupported":
                return 2;
            case "network":
            case "generic":
                return 1;
            default:
                return 1;
        }
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
        HlsPlaylist playlist = HlsPlaylist.parse(text, actualUrl);
        MediaCandidate c = new MediaCandidate(actualUrl, page, "Video", MediaCandidate.HLS);
        c.mime = "application/vnd.apple.mpegurl";
        if (!playlist.master) {
            c.quality = "Best available";
            return c;
        }
        java.util.ArrayList<MediaCandidate.Quality> qualities = new java.util.ArrayList<>();
        java.util.ArrayList<HlsPlaylist.Variant> variants = new java.util.ArrayList<>(playlist.variants);
        variants.sort((a, b) -> Long.compare(b.bandwidth, a.bandwidth));
        for (HlsPlaylist.Variant v : variants) {
            // The lightweight HLS path cannot merge a separate audio rendition.
            if (!playlist.audioRenditions.isEmpty() && playlist.audioRenditions.contains(v.audioGroup)) continue;
            MediaCandidate.Quality q = new MediaCandidate.Quality();
            q.id = v.url;
            q.url = v.url;
            q.label = v.label();
            q.quality = v.resolution.isEmpty() ? v.label() : v.resolution;
            q.ext = "HLS";
            q.height = height(v.resolution);
            q.hasAudio = true;
            qualities.add(q);
        }
        if (qualities.isEmpty()) throw new IOException("No variant with combined audio and video was found.");
        c.qualities = qualities;
        c.selectQuality(qualities.get(0));
        return c;
    }

    private static int height(String resolution) {
        if (resolution == null) return 0;
        String[] parts = resolution.toLowerCase(java.util.Locale.ROOT).split("x");
        if (parts.length != 2) return 0;
        try { return Integer.parseInt(parts[1].trim()); } catch (NumberFormatException e) { return 0; }
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
