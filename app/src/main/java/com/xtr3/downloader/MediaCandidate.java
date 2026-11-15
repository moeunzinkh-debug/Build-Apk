package com.xtr3.downloader;

import java.io.Serializable;
import java.util.Map;

/** A user-selectable media URL. No cookies or credentials are persisted. */
public final class MediaCandidate implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final String DIRECT = "direct";
    public static final String HLS = "hls";

    public String url;
    public String pageUrl;
    public String title;
    public String mime;
    public String kind = DIRECT;
    public String quality = "";
    public long size = -1;
    /** Replay-safe headers from the player's own request; lives only in memory. */
    public Map<String, String> headers;

    public MediaCandidate() { }

    public MediaCandidate(String url, String pageUrl, String title, String kind) {
        this.url = url;
        this.pageUrl = pageUrl;
        this.title = title;
        this.kind = kind;
    }
}
