package com.xtr3.downloader;

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
    private final HttpMediaClient client = new HttpMediaClient();
    private volatile boolean cancelled;
    private volatile HttpURLConnection current;

    public void cancel() {
        cancelled = true;
        HttpURLConnection c = current;
        if (c != null) c.disconnect();
    }

    public Result resolve(String input) throws IOException {
        String url = LinkParser.normalize(input);
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
