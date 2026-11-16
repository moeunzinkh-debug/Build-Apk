package com.xtr3.downloader;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Map;

/** A user-selectable media URL. No cookies or credentials are persisted. */
public final class MediaCandidate implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final String DIRECT = "direct";
    public static final String HLS = "hls";
    public static final String PLATFORM = "platform";

    /** One real, selectable quality. Platform options carry a yt-dlp format selector; HLS options carry a variant URL. */
    public static final class Quality implements Serializable {
        private static final long serialVersionUID = 1L;
        public String id = "";
        public String label = "";
        public String quality = "";
        public String ext = "";
        public String url;
        public long size = -1;
        public int height;
        public boolean hasAudio = true;

        public Quality() { }
        public Quality(String id, String label, String quality, String ext, long size, int height, boolean hasAudio) {
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label;
            this.quality = quality == null ? "" : quality;
            this.ext = ext == null ? "" : ext;
            this.size = size;
            this.height = height;
            this.hasAudio = hasAudio;
        }
    }

    public String url;
    public String pageUrl;
    public String title;
    public String mime;
    public String kind = DIRECT;
    public String quality = "";
    public long size = -1;
    public String site;
    public String platformNote = "";
    /** Small, serializable quality list shown before the user confirms a download. */
    public ArrayList<Quality> qualities;
    public Quality selectedQuality;
    /** Private cache file containing the single-video yt-dlp info JSON, so download need not extract again. */
    public String platformInfoPath;
    /** Replay-safe headers from the player's own request; lives only in memory. */
    public Map<String, String> headers;

    public MediaCandidate() { }

    public MediaCandidate(String url, String pageUrl, String title, String kind) {
        this.url = url;
        this.pageUrl = pageUrl;
        this.title = title;
        this.kind = kind;
    }

    public void selectQuality(Quality quality) {
        selectedQuality = quality;
        if (quality == null) return;
        if (DIRECT.equals(kind) && quality.url != null && !quality.url.isEmpty()) this.url = quality.url;
        if (quality.quality != null && !quality.quality.isEmpty()) this.quality = quality.quality;
        else if (quality.label != null && !quality.label.isEmpty()) this.quality = quality.label;
        size = quality.size > 0 ? quality.size : -1;
    }
}
