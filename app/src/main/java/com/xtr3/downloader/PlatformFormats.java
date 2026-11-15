package com.xtr3.downloader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a small, honest quality menu from one yt-dlp single-video info dict. */
public final class PlatformFormats {
    private static final int MAX_OPTIONS = 10;

    private PlatformFormats() { }

    public static List<MediaCandidate.Quality> fromInfo(JSONObject info) {
        ArrayList<MediaCandidate.Quality> out = new ArrayList<>();
        if (info == null) return out;
        JSONArray formats = info.optJSONArray("formats");
        if (formats == null) formats = info.optJSONArray("requested_formats");
        if (formats == null || formats.length() == 0) return out;

        ArrayList<JSONObject> videos = new ArrayList<>();
        ArrayList<JSONObject> audios = new ArrayList<>();
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (!usable(f)) continue;
            boolean video = hasCodec(f.optString("vcodec", ""));
            boolean audio = hasCodec(f.optString("acodec", ""));
            if (video && f.optInt("height", 0) > 0) videos.add(f);
            if (audio && !video) audios.add(f);
            if (video && audio && f.optInt("height", 0) > 0) {
                MediaCandidate.Quality q = option(f, null);
                if (q != null) out.add(q);
            }
        }

        audios.sort(Comparator.comparingInt(PlatformFormats::audioScore).reversed()
                .thenComparing(Comparator.comparingDouble(PlatformFormats::bitrate).reversed()));
        JSONObject bestAudio = audios.isEmpty() ? null : audios.get(0);
        for (JSONObject v : videos) {
            if (hasCodec(v.optString("acodec", ""))) continue; // muxed already added
            MediaCandidate.Quality q = option(v, bestAudio);
            if (q != null) out.add(q);
        }

        // One clear choice per resolution/container/audio shape, best codec/bitrate first.
        Map<String, MediaCandidate.Quality> byKey = new LinkedHashMap<>();
        out.sort(Comparator.comparingInt((MediaCandidate.Quality q) -> q.height).reversed()
                .thenComparing((MediaCandidate.Quality q) -> !q.hasAudio)
                .thenComparingInt(q -> extScore(q.ext))
                .thenComparing(Comparator.comparingLong((MediaCandidate.Quality q) -> q.size).reversed())
                .thenComparing((MediaCandidate.Quality q) -> q.label));
        for (MediaCandidate.Quality q : out) {
            String key = q.height + "|" + q.ext.toLowerCase(Locale.ROOT) + "|" + q.hasAudio;
            byKey.putIfAbsent(key, q);
        }
        ArrayList<MediaCandidate.Quality> result = new ArrayList<>(byKey.values());
        result.sort(Comparator.comparingInt((MediaCandidate.Quality q) -> q.height).reversed()
                .thenComparing((MediaCandidate.Quality q) -> !q.hasAudio)
                .thenComparingInt(q -> extScore(q.ext))
                .thenComparing((MediaCandidate.Quality q) -> q.label));
        if (result.size() > MAX_OPTIONS) return new ArrayList<>(result.subList(0, MAX_OPTIONS));
        return result;
    }

    /** Prefer a high-quality option with sound at or below 1080p; otherwise the best available. */
    public static MediaCandidate.Quality defaultChoice(List<MediaCandidate.Quality> qualities) {
        if (qualities == null || qualities.isEmpty()) return null;
        MediaCandidate.Quality best = null;
        for (MediaCandidate.Quality q : qualities) {
            if (!q.hasAudio) continue;
            if (q.height > 0 && q.height <= 1080) return q;
            if (best == null || q.height > best.height) best = q;
        }
        return best != null ? best : qualities.get(0);
    }

    public static boolean validSelector(String selector) {
        return selector != null && !selector.isEmpty() && selector.length() <= 256
                && !selector.startsWith("-") && selector.matches("[A-Za-z0-9_.:+/-]+");
    }

    private static MediaCandidate.Quality option(JSONObject video, JSONObject audio) {
        String videoId = cleanId(video.optString("format_id", ""));
        if (videoId.isEmpty()) return null;
        boolean muxed = hasCodec(video.optString("acodec", ""));
        String selector = videoId;
        long size = size(video);
        boolean hasAudio = muxed;
        String ext = ext(video.optString("ext", ""));
        if (!muxed && audio != null) {
            String audioId = cleanId(audio.optString("format_id", ""));
            if (audioId.isEmpty()) return null;
            selector = videoId + "+" + audioId;
            hasAudio = true;
            long audioSize = size(audio);
            size = size > 0 && audioSize > 0 ? size + audioSize : -1;
            ext = mergeExt(ext, ext(audio.optString("ext", "")));
        }
        if (!validSelector(selector)) return null;
        int height = video.optInt("height", 0);
        int fps = video.optInt("fps", 0);
        String quality = height + "p" + (fps > 30 ? String.valueOf(fps) : "");
        String label = quality + " · " + ext.toUpperCase(Locale.ROOT);
        return new MediaCandidate.Quality(selector, label, quality, ext, size, height, hasAudio);
    }

    private static boolean usable(JSONObject f) {
        if (f == null) return false;
        if (f.optBoolean("has_drm", false) || !f.optString("drm", "").isEmpty()) return false;
        String id = cleanId(f.optString("format_id", ""));
        if (id.isEmpty()) return false;
        String ext = f.optString("ext", "").toLowerCase(Locale.ROOT);
        String note = f.optString("format_note", "").toLowerCase(Locale.ROOT);
        String protocol = f.optString("protocol", "").toLowerCase(Locale.ROOT);
        if (ext.equals("mhtml") || ext.equals("jpg") || ext.equals("png") || note.contains("storyboard")) return false;
        if (protocol.equals("mhtml")) return false;
        String url = f.optString("url", "");
        JSONArray fragments = f.optJSONArray("fragments");
        String manifest = f.optString("manifest_url", "");
        return !url.isEmpty() || !manifest.isEmpty() || (fragments != null && fragments.length() > 0);
    }

    private static String cleanId(String id) {
        if (id == null) return "";
        String value = id.trim();
        return value.matches("[A-Za-z0-9_.:-]+") ? value : "";
    }

    private static boolean hasCodec(String codec) {
        if (codec == null) return false;
        String c = codec.toLowerCase(Locale.ROOT);
        return !c.isEmpty() && !c.equals("none") && !c.equals("null");
    }

    private static long size(JSONObject f) {
        long n = f.optLong("filesize", -1);
        if (n <= 0) n = f.optLong("filesize_approx", -1);
        return n > 0 ? n : -1;
    }

    private static double bitrate(JSONObject f) {
        double n = f.optDouble("tbr", 0);
        if (n <= 0) n = f.optDouble("vbr", 0);
        if (n <= 0) n = f.optDouble("abr", 0);
        return Double.isFinite(n) ? n : 0;
    }

    private static int audioScore(JSONObject f) {
        String codec = f.optString("acodec", "").toLowerCase(Locale.ROOT);
        String ext = f.optString("ext", "").toLowerCase(Locale.ROOT);
        if (codec.contains("mp4a") || codec.contains("aac") || ext.equals("m4a") || ext.equals("mp4")) return 3;
        if (codec.contains("opus") || ext.equals("webm")) return 2;
        return 1;
    }

    private static int extScore(String ext) {
        if ("mp4".equals(ext)) return 0;
        if ("mkv".equals(ext)) return 1;
        if ("webm".equals(ext)) return 2;
        return 3;
    }

    private static String ext(String value) {
        String e = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (e.isEmpty() || e.equals("none")) return "mp4";
        if (e.equals("m4a")) return "mp4";
        return e;
    }

    private static String mergeExt(String video, String audio) {
        if ((video.equals("mp4") && audio.equals("m4a")) || (video.equals("m4a") && audio.equals("mp4"))
                || (video.equals("mp4") && audio.equals("mp4"))) return "mp4";
        // The transfer asks FFmpeg for mp4/mkv only; anything else is losslessly packaged as MKV.
        return "mkv";
    }
}
