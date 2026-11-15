package com.xtr3.downloader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PlatformFormatsTest {
    private static JSONObject format(String id, String ext, int height, String vcodec, String acodec, long size) throws Exception {
        JSONObject f = new JSONObject();
        f.put("format_id", id);
        f.put("ext", ext);
        f.put("height", height);
        f.put("vcodec", vcodec);
        f.put("acodec", acodec);
        f.put("url", "https://cdn.test/" + id);
        if (size > 0) f.put("filesize", size);
        return f;
    }

    @Test public void mergesVideoOnlyWithBestAudioAndKeepsMuxedChoice() throws Exception {
        JSONObject info = new JSONObject().put("formats", new JSONArray()
                .put(format("18", "mp4", 360, "h264", "aac", 1000))
                .put(format("137", "mp4", 1080, "h264", "none", 9000))
                .put(format("248", "webm", 1080, "vp9", "none", 8000))
                .put(format("140", "m4a", 0, "none", "aac", 500))
                .put(format("251", "webm", 0, "none", "opus", 400)));
        List<MediaCandidate.Quality> q = PlatformFormats.fromInfo(info);
        assertEquals(3, q.size());
        assertEquals("137+140", q.get(0).id);
        assertEquals(9500, q.get(0).size);
        assertTrue(q.get(0).hasAudio);
        assertEquals("mp4", q.get(0).ext);
        assertEquals("18", q.get(2).id);
        assertEquals(PlatformFormats.defaultChoice(q).id, q.get(0).id);
    }

    @Test public void skipsDrmStoryboardsAndUnsafeIds() throws Exception {
        JSONObject drm = format("drm", "mp4", 1080, "h264", "aac", 1000);
        drm.put("has_drm", true);
        JSONObject storyboard = format("sb0", "mhtml", 0, "none", "none", -1);
        JSONObject unsafe = format("1;rm", "mp4", 720, "h264", "aac", 1000);
        JSONObject info = new JSONObject().put("formats", new JSONArray()
                .put(drm).put(storyboard).put(unsafe).put(format("18", "mp4", 360, "h264", "aac", 1000)));
        List<MediaCandidate.Quality> q = PlatformFormats.fromInfo(info);
        assertEquals(1, q.size());
        assertEquals("18", q.get(0).id);
    }

    @Test public void marksVideoOnlyWhenNoAudioExists() throws Exception {
        JSONObject info = new JSONObject().put("formats", new JSONArray()
                .put(format("137", "mp4", 1080, "h264", "none", 9000)));
        List<MediaCandidate.Quality> q = PlatformFormats.fromInfo(info);
        assertEquals(1, q.size());
        assertFalse(q.get(0).hasAudio);
        assertEquals("137", q.get(0).id);
    }

    @Test public void webmVideoWithOpusAudioIsPackagedAsMkv() throws Exception {
        JSONObject info = new JSONObject().put("formats", new JSONArray()
                .put(format("248", "webm", 1080, "vp9", "none", 8000))
                .put(format("251", "webm", 0, "none", "opus", 400)));
        List<MediaCandidate.Quality> q = PlatformFormats.fromInfo(info);
        assertEquals(1, q.size());
        assertEquals("248+251", q.get(0).id);
        assertEquals("mkv", q.get(0).ext);
        assertTrue(q.get(0).hasAudio);
        assertEquals(8400, q.get(0).size);
    }

    @Test public void defaultPrefersAudioBearingUpTo1080pAndMenuIsBounded() throws Exception {
        JSONArray formats = new JSONArray();
        for (int h = 144; h <= 2160; h += 144) formats.put(format("f" + h, "mp4", h, "h264", "aac", h));
        List<MediaCandidate.Quality> q = PlatformFormats.fromInfo(new JSONObject().put("formats", formats));
        assertEquals(10, q.size());
        assertEquals(2160, q.get(0).height);
        assertEquals(1008, PlatformFormats.defaultChoice(q).height);
        MediaCandidate.Quality videoOnly = new MediaCandidate.Quality("137", "1080p · MP4", "1080p", "mp4", 9000, 1080, false);
        assertEquals(videoOnly, PlatformFormats.defaultChoice(java.util.Collections.singletonList(videoOnly)));
    }

    @Test public void selectorValidationRejectsOptionsAndShellSyntax() {
        assertTrue(PlatformFormats.validSelector("137+140"));
        assertTrue(PlatformFormats.validSelector("hls-1080p-1"));
        assertFalse(PlatformFormats.validSelector("--exec"));
        assertFalse(PlatformFormats.validSelector("18;touch /tmp/x"));
        assertFalse(PlatformFormats.validSelector(""));
        assertFalse(PlatformFormats.validSelector(null));
    }
}
