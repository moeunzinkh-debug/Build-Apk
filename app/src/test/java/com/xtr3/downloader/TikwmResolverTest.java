package com.xtr3.downloader;

import org.junit.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

public class TikwmResolverTest {
    @Test public void parsesHdStandardAndWatermarkedQualitiesAndResolvesRelativePaths() throws IOException {
        String json = "{"
                + "\"code\":0,"
                + "\"msg\":\"success\","
                + "\"data\":{"
                + "\"id\":\"7123456789012345678\","
                + "\"title\":\"Micro drama &amp; EP 8\","
                + "\"hdplay\":\"/video/media/hdplay/7123456789012345678.mp4\","
                + "\"hd_size\":24500000,"
                + "\"play\":\"https://v16m-default.tiktokcdn.com/tos-alisg/clip.mp4?expire=199999\","
                + "\"size\":11800000,"
                + "\"wmplay\":\"/video/media/wmplay/7123456789012345678.mp4\","
                + "\"wm_size\":12400000"
                + "}}";
        TikwmResolver.Result result = TikwmResolver.parseResponse(json);
        assertEquals("https://www.tikwm.com/video/media/hdplay/7123456789012345678.mp4", result.url);
        assertEquals("Micro drama & EP 8", result.title);
        assertEquals(24_500_000L, result.size);
        assertEquals(3, result.qualities.size());

        MediaCandidate.Quality hd = result.qualities.get(0);
        assertEquals("hdplay", hd.id);
        assertEquals("HD", hd.quality);
        assertEquals("HD (no watermark) · MP4", hd.label);
        assertEquals(24_500_000L, hd.size);
        assertTrue(hd.hasAudio);

        MediaCandidate.Quality sd = result.qualities.get(1);
        assertEquals("play", sd.id);
        assertEquals("Standard", sd.quality);
        assertEquals("https://v16m-default.tiktokcdn.com/tos-alisg/clip.mp4?expire=199999", sd.url);
        assertEquals(11_800_000L, sd.size);

        MediaCandidate.Quality wm = result.qualities.get(2);
        assertEquals("wmplay", wm.id);
        assertEquals("Watermarked", wm.quality);
        assertEquals("https://www.tikwm.com/video/media/wmplay/7123456789012345678.mp4", wm.url);
    }

    @Test public void deduplicatesIdenticalUrlsWhileKeepingReportedSize() throws IOException {
        String json = "{\"code\":0,\"data\":{"
                + "\"title\":\"Episode 2\","
                + "\"hdplay\":\"https://p16-sign-va.tiktokcdn.com/same.mp4\","
                + "\"hd_size\":0,"
                + "\"play\":\"https://p16-sign-va.tiktokcdn.com/same.mp4\","
                + "\"size\":9100000"
                + "}}";
        TikwmResolver.Result result = TikwmResolver.parseResponse(json);
        assertEquals(1, result.qualities.size());
        assertEquals(9_100_000L, result.size);
        assertEquals(9_100_000L, result.qualities.get(0).size);
    }

    @Test public void handlesJsonNullAndMissingHdGracefully() throws IOException {
        String json = "{\"code\":0,\"data\":{"
                + "\"title\":null,"
                + "\"hdplay\":null,"
                + "\"play\":\"/video/media/play/999.mp4\","
                + "\"size\":5000000,"
                + "\"wmplay\":\"\""
                + "}}";
        TikwmResolver.Result result = TikwmResolver.parseResponse(json);
        assertEquals("https://www.tikwm.com/video/media/play/999.mp4", result.url);
        assertEquals("", result.title);
        assertEquals(1, result.qualities.size());
        assertEquals("Standard", result.qualities.get(0).quality);
    }

    @Test public void detectsPhotoSlideCarouselsBeforeAudioLinks() {
        String json = "{\"code\":0,\"data\":{"
                + "\"images\":[\"https://p16.ibyteimg.com/1.jpeg\",\"https://p16.ibyteimg.com/2.jpeg\"],"
                + "\"play\":\"https://sf16.iescdn.com/obj/music.mp3\""
                + "}}";
        try {
            TikwmResolver.parseResponse(json);
            fail("Expected slides category");
        } catch (IOException e) {
            assertEquals("slides", e.getMessage());
        }
    }

    @Test public void detectsAudioOnlyPostsWhenNoVideoStreamExists() {
        String json = "{\"code\":0,\"data\":{"
                + "\"play\":\"https://www.tikwm.com/video/music/track.mp3\","
                + "\"music\":\"https://www.tikwm.com/video/music/track.mp3\""
                + "}}";
        try {
            TikwmResolver.parseResponse(json);
            fail("Expected audio category");
        } catch (IOException e) {
            assertEquals("audio", e.getMessage());
        }
    }

    @Test public void rejectsHtmlChallengeNonHttpsCredentialsAndForeignHosts() {
        try {
            TikwmResolver.parseResponse("<!DOCTYPE html><html><title>Just a moment...</title></html>");
            fail("Expected challenge category");
        } catch (IOException e) {
            assertEquals("challenge", e.getMessage());
        }

        assertNull(TikwmResolver.resolveHttpsUrl("http://p16.tiktokcdn.com/video.mp4"));
        assertNull(TikwmResolver.resolveHttpsUrl("https://user:pass@www.tikwm.com/video.mp4"));
        assertNull(TikwmResolver.resolveHttpsUrl("javascript:alert(1)"));

        String foreignHostJson = "{\"code\":0,\"data\":{\"play\":\"https://evil.example.test/video.mp4\"}}";
        try {
            TikwmResolver.parseResponse(foreignHostJson);
            fail("Expected forbidden category");
        } catch (IOException e) {
            assertEquals("forbidden", e.getMessage());
        }
    }

    @Test public void allowsTikwmAndRegionalByteDanceCdnsOnly() {
        assertTrue(TikwmResolver.isAllowedMediaHost("www.tikwm.com"));
        assertTrue(TikwmResolver.isAllowedMediaHost("tikwm.com"));
        assertTrue(TikwmResolver.isTikwmHost("www.tikwm.com"));
        assertFalse(TikwmResolver.isTikwmHost("p16-sign-va.tiktokcdn.com"));
        assertTrue(TikwmResolver.isAllowedMediaHost("p16-sign-va.tiktokcdn.com"));
        assertTrue(TikwmResolver.isAllowedMediaHost("v16m.tiktokv.com"));
        assertTrue(TikwmResolver.isAllowedMediaHost("v19.tiktokcdn-eu.com"));
        assertFalse(TikwmResolver.isAllowedMediaHost("tikwm.com.evil.test"));
        assertFalse(TikwmResolver.isAllowedMediaHost("nottikwm.com"));
        assertFalse(TikwmResolver.isAllowedMediaHost("127.0.0.1"));
        assertFalse(TikwmResolver.isAllowedMediaHost(null));
    }

    @Test public void mapsErrorMessagesToLocalizableCategories() {
        assertEquals("rate", TikwmResolver.messageCategory("Free Api Limit: 1 request / second."));
        assertEquals("unsupported", TikwmResolver.messageCategory("Url parsing is failed! Please check url."));
        assertEquals("login", TikwmResolver.messageCategory("Video is private or requires login"));
        assertEquals("paid", TikwmResolver.messageCategory("Unlock VIP episode to watch"));
        assertEquals("unavailable", TikwmResolver.messageCategory("Video not found or deleted"));
        assertEquals("generic", TikwmResolver.messageCategory(null));
    }

    @Test public void selectQualityUpdatesDirectUrlWithoutMutatingPlatformOrHlsUrl() throws IOException {
        String json = "{\"code\":0,\"data\":{"
                + "\"hdplay\":\"/video/media/hdplay/1.mp4\",\"hd_size\":20000000,"
                + "\"play\":\"https://v16m.tiktokcdn.com/sd.mp4\",\"size\":9000000}}";
        TikwmResolver.Result resolved = TikwmResolver.parseResponse(json);
        List<MediaCandidate.Quality> qualities = resolved.qualities;

        MediaCandidate direct = new MediaCandidate(resolved.url, "https://www.tiktok.com/@u/video/1", "EP 1", MediaCandidate.DIRECT);
        direct.qualities = new java.util.ArrayList<>(qualities);
        direct.selectQuality(qualities.get(1));
        assertEquals("https://v16m.tiktokcdn.com/sd.mp4", direct.url);
        assertEquals("Standard", direct.quality);
        assertEquals(9_000_000L, direct.size);

        MediaCandidate hls = new MediaCandidate("https://cdn.test/master.m3u8", "https://cdn.test/", "HLS", MediaCandidate.HLS);
        MediaCandidate.Quality variant = new MediaCandidate.Quality("v1", "720p", "1280x720", "HLS", -1, 720, true);
        variant.url = "https://cdn.test/720p.m3u8";
        hls.selectQuality(variant);
        assertEquals("https://cdn.test/master.m3u8", hls.url);
    }

    @Test public void chooseFailurePrefersSpecificDiagnosisOverGenericFallbackErrors() {
        assertEquals("login", MediaResolver.chooseFailure(
                new IOException("login"), new IOException("unavailable"), new IOException("challenge")).getMessage());
        assertEquals("challenge", MediaResolver.chooseFailure(
                new IOException("unsupported"), new IOException("network"), new IOException("challenge")).getMessage());
        assertEquals("rate", MediaResolver.chooseFailure(
                new IOException("unsupported"), new IOException("rate"), new IOException("network")).getMessage());
    }
}
