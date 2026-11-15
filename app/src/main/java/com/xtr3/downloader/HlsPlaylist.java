package com.xtr3.downloader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small VOD HLS parser. DRM and live streaming are deliberately not supported. */
public final class HlsPlaylist {
    public static final class Variant {
        public String url;
        public long bandwidth;
        public String resolution = "";
        public String codecs = "";
        public String audioGroup = "";
        public String label() {
            String r = resolution.isEmpty() ? "" : resolution + " · ";
            return r + (bandwidth > 0 ? String.format(Locale.ROOT, "%.1f Mbps", bandwidth / 1_000_000.0) : "HLS");
        }
    }
    public static final class Key {
        public String method = "NONE";
        public String url;
        public String iv;
    }
    public static final class Segment {
        public String url;
        public double duration;
        public long sequence;
        public long offset = -1;
        public long length = -1;
        public Key key;
    }
    public boolean master;
    public boolean endList;
    public long mediaSequence;
    public double duration;
    public String mapUrl;
    public Key mapKey;
    public String unsupported;
    public final List<Variant> variants = new ArrayList<>();
    public final List<Segment> segments = new ArrayList<>();
    public final List<String> audioRenditions = new ArrayList<>();

    public static HlsPlaylist parse(String text, String baseUrl) {
        if (text == null || !text.trim().startsWith("#EXTM3U")) {
            throw new IllegalArgumentException("The server did not return a valid HLS playlist. The link may have expired.");
        }
        HlsPlaylist p = new HlsPlaylist();
        p.master = text.contains("#EXT-X-STREAM-INF");
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        Key key = new Key(), pendingKey = null;
        Variant pendingVariant = null;
        double pendingDuration = 0;
        long sequence = 0, pendingOffset = -1, pendingLength = -1, previousEnd = 0;
        boolean sawMap = false, sawDiscontinuity = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> a = LinkParser.listAttributes(line.substring(line.indexOf(':') + 1));
                pendingVariant = new Variant();
                pendingVariant.bandwidth = number(a.get("BANDWIDTH"));
                if (pendingVariant.bandwidth == 0) pendingVariant.bandwidth = number(a.get("AVERAGE-BANDWIDTH"));
                pendingVariant.resolution = a.getOrDefault("RESOLUTION", "");
                pendingVariant.codecs = a.getOrDefault("CODECS", "");
                pendingVariant.audioGroup = a.getOrDefault("AUDIO", "");
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = LinkParser.listAttributes(line.substring(line.indexOf(':') + 1));
                if ("AUDIO".equalsIgnoreCase(a.get("TYPE")) && a.containsKey("URI")) {
                    p.audioRenditions.add(a.getOrDefault("GROUP-ID", ""));
                }
            } else if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                Key parsed = parseKey(line.substring(line.indexOf(':') + 1), baseUrl);
                if (line.startsWith("#EXT-X-SESSION-KEY:")) {
                    if (!"NONE".equals(parsed.method)) key = parsed;
                } else pendingKey = parsed;
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> a = LinkParser.listAttributes(line.substring(line.indexOf(':') + 1));
                String url = LinkParser.resolve(baseUrl, a.get("URI"));
                if (url == null) p.unsupported = "The HLS initialization segment is invalid.";
                if (sawMap && !urlEquals(p.mapUrl, url)) p.unsupported = "HLS with changing initialization segments is not supported.";
                if (a.containsKey("BYTERANGE")) p.unsupported = "Byte-ranged initialization segments are not supported.";
                if (pendingKey != null) { key = pendingKey; pendingKey = null; }
                p.mapUrl = url;
                p.mapKey = key;
                sawMap = true;
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = number(line.substring(line.indexOf(':') + 1));
                p.mediaSequence = sequence;
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                p.endList = true;
            } else if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                sawDiscontinuity = true;
                previousEnd = 0;
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                String[] range = line.substring(line.indexOf(':') + 1).split("@", 2);
                pendingLength = number(range[0]);
                pendingOffset = range.length == 2 ? number(range[1]) : previousEnd;
                if (pendingLength <= 0 || pendingLength > 64L * 1024 * 1024) {
                    p.unsupported = "This HLS segment has an invalid or oversized byte range.";
                }
            } else if (line.startsWith("#EXTINF:")) {
                String d = line.substring(8).split(",", 2)[0];
                try { pendingDuration = Double.parseDouble(d); } catch (NumberFormatException e) { pendingDuration = 0; }
                if (!Double.isFinite(pendingDuration) || pendingDuration < 0) pendingDuration = 0;
                p.duration += pendingDuration;
            } else if (line.startsWith("#")) {
                continue;
            } else {
                String url = LinkParser.resolve(baseUrl, line);
                if (url == null) throw new IllegalArgumentException("The playlist contains an invalid media URL.");
                if (p.master) {
                    if (pendingVariant == null) pendingVariant = new Variant();
                    pendingVariant.url = url;
                    p.variants.add(pendingVariant);
                    pendingVariant = null;
                } else {
                    if (pendingKey != null) { key = pendingKey; pendingKey = null; }
                    Segment s = new Segment();
                    s.url = url;
                    s.duration = pendingDuration;
                    s.sequence = sequence++;
                    s.key = key;
                    s.offset = pendingOffset;
                    s.length = pendingLength;
                    if (pendingOffset >= 0) previousEnd = pendingOffset + pendingLength;
                    p.segments.add(s);
                    pendingDuration = 0;
                    pendingOffset = pendingLength = -1;
                    if (p.segments.size() > 10_000) throw new IllegalArgumentException("This playlist is too large.");
                }
            }
        }
        if (!p.master && !p.endList) p.unsupported = "Live HLS streams cannot be saved. Use a finished video.";
        if (!p.master && p.segments.isEmpty()) p.unsupported = "The playlist contains no video segments.";
        if (!p.master && sawMap && sawDiscontinuity) p.unsupported = "Fragmented MP4 HLS with discontinuities is not supported.";
        if (!p.master && !p.audioRenditions.isEmpty()) p.unsupported = "HLS with a separate audio playlist is not supported by this lightweight build.";
        if (p.master && p.variants.isEmpty()) p.unsupported = "The master playlist contains no playable variants.";
        if (p.unsupported != null) throw new IllegalArgumentException(p.unsupported);
        return p;
    }

    private static Key parseKey(String attributes, String base) {
        Map<String, String> a = LinkParser.listAttributes(attributes);
        Key k = new Key();
        k.method = a.getOrDefault("METHOD", "NONE").toUpperCase(Locale.ROOT);
        if ("NONE".equals(k.method)) return k;
        String format = a.getOrDefault("KEYFORMAT", "identity");
        if (!"AES-128".equals(k.method) || !"identity".equals(format)) {
            throw new IllegalArgumentException("Encrypted or DRM-protected video (SAMPLE-AES/Widevine) is not supported.");
        }
        k.url = LinkParser.resolve(base, a.get("URI"));
        k.iv = a.get("IV");
        if (k.url == null || (k.iv != null && !k.iv.matches("(?i)(0x)?[0-9a-f]{32}"))) {
            throw new IllegalArgumentException("The HLS encryption key or IV is invalid.");
        }
        return k;
    }

    public static Variant bestVariant(HlsPlaylist playlist) {
        Variant best = null;
        for (Variant v : playlist.variants) {
            if (!playlist.audioRenditions.isEmpty() && playlist.audioRenditions.contains(v.audioGroup)) continue;
            if (best == null || v.bandwidth > best.bandwidth) best = v;
        }
        if (best == null) throw new IllegalArgumentException("No variant with combined audio and video was found.");
        return best;
    }

    public boolean fragmentedMp4() {
        if (mapUrl != null) return true;
        for (Segment s : segments) {
            String p = LinkParser.path(s.url);
            if (p.endsWith(".m4s") || p.endsWith(".mp4") || p.endsWith(".m4v")) {
                throw new IllegalArgumentException("Fragmented MP4 segments need an EXT-X-MAP initialization segment.");
            }
        }
        return false;
    }

    private static boolean urlEquals(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static long number(String value) {
        try { return value == null ? 0 : Long.parseLong(value.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
