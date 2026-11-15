package com.xtr3.downloader;

import org.junit.Test;
import static org.junit.Assert.*;

public class SsstikResolverTest {
    /** A signed CDN URL whose base64 spans TWO path segments, because '/' is in the alphabet. */
    private static final String TARGET =
            "https://p16-sign-va.tiktokcdn.com/tos-alisg-v/aabc123~noop.mp4?nonce=26013&refresh_token=1b5265bb&t=223449c4";
    private static final String WRAPPED =
            "https://tikcdn.io/ssstik/a/aHR0cHM6Ly9wMTYtc2lnbi12YS50aWt0b2tjZG4uY29tL3Rvcy1hbGlzZy12L2FhYmMxMjN+"
            + "bm9vcC5tcDQ/bm9uY2U9MjYwMTMmcmVmcmVzaF90b2tlbj0xYjUyNjViYiZ0PTIyMzQ0OWM0";

    @Test public void findsThePageTokenInBothPublishedSpellings() {
        assertEquals("gfFrG7", SsstikResolver.extractPageToken("<script>var s_tt = 'gfFrG7';</script>"));
        assertEquals("KWosH6", SsstikResolver.extractPageToken("<form include-vals=\"tt:'KWosH6'\" hx-post=\"/abc\">"));
    }

    @Test public void neverInventsATokenWhenThePageIsAChallenge() {
        assertNull(SsstikResolver.extractPageToken("<html>Just a moment... Enable JavaScript</html>"));
        assertNull(SsstikResolver.extractPageToken(null));
        assertNull(SsstikResolver.extractPageToken("tt:'ab'"));
    }

    /**
     * The regression that broke most episodes: decoding only the final path segment.
     * Here the tail decodes to plain text but not to a URL, so a last-segment-only
     * reader finds nothing while the rejoined span yields the real signed URL.
     */
    @Test public void rejoinsBase64SplitAcrossSeveralPathSegments() {
        String lastSegment = WRAPPED.substring(WRAPPED.lastIndexOf('/') + 1);
        assertNull("the tail alone must not pass as a URL", SsstikResolver.decodeBase64UrlSegment(lastSegment));
        SsstikResolver.Result result = SsstikResolver.unwrapMediaUrl(WRAPPED, "Episode 8");
        assertNotNull(result);
        assertTrue(result.decoded);
        assertEquals(TARGET, result.url);
        assertEquals("Episode 8", result.title);
    }

    @Test public void leavesOpaqueProxyPathsAndForeignHostsAlone() {
        SsstikResolver.Result proxy = SsstikResolver.unwrapMediaUrl("https://ssscdn.io/dl/abc123", "");
        assertNotNull(proxy);
        assertFalse(proxy.decoded);
        assertEquals("https://ssscdn.io/dl/abc123", proxy.url);
        SsstikResolver.Result direct = SsstikResolver.unwrapMediaUrl(TARGET, "");
        assertNotNull(direct);
        assertFalse(direct.decoded);
        assertEquals(TARGET, direct.url);
    }

    @Test public void rejectsBinaryNonHttpsAndCredentialBearingDecodes() {
        assertNull(SsstikResolver.decodeBase64UrlSegment(java.util.Base64.getEncoder()
                .encodeToString(new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc, (byte) 0xfb, (byte) 0xfa,
                        (byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc, (byte) 0xfb, (byte) 0xfa,
                        (byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc, (byte) 0xfb, (byte) 0xfa})));
        assertNull(SsstikResolver.decodeBase64UrlSegment(java.util.Base64.getEncoder()
                .encodeToString("http://p16.tiktokcdn.com/video.mp4".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertNull(SsstikResolver.decodeBase64UrlSegment(java.util.Base64.getEncoder()
                .encodeToString("https://user:pass@p16.tiktokcdn.com/v.mp4".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertNull(SsstikResolver.decodeBase64UrlSegment("short"));
    }

    @Test public void allowsRegionalByteDanceCdnsButNotLookalikes() {
        assertTrue(SsstikResolver.isAllowedMediaHost("p16-sign-va.tiktokcdn.com"));
        assertTrue(SsstikResolver.isAllowedMediaHost("v19.tiktokcdn-eu.com"));
        assertTrue(SsstikResolver.isAllowedMediaHost("p16.ibyteimg.com"));
        assertTrue(SsstikResolver.isAllowedMediaHost("tikcdn.io"));
        assertFalse(SsstikResolver.isAllowedMediaHost("tiktokcdn.com.evil.test"));
        assertFalse(SsstikResolver.isAllowedMediaHost("nottikcdn.io"));
        assertFalse(SsstikResolver.isAllowedMediaHost("127.0.0.1"));
        assertFalse(SsstikResolver.isAllowedMediaHost(null));
    }

    @Test public void readsBareAndJsonHxTriggerVerdictsAndIgnoresUnknownEvents() {
        assertEquals("ssssuccess_video", SsstikResolver.parseSignal("ssssuccess_video"));
        assertEquals("ssstokenfail", SsstikResolver.parseSignal("{\"ssstokenfail\":\"v=1\"}"));
        assertEquals("ssslimitexceed", SsstikResolver.parseSignal(" ssslimitexceed "));
        assertNull(SsstikResolver.parseSignal("someOtherEvent"));
        assertNull(SsstikResolver.parseSignal(null));
        // The paid HD tier must never be blamed for a missing MP4.
        assertNull(SsstikResolver.parseSignal("sssrapidapisuccess"));
    }

    @Test public void mapsVerdictsToStableLocalizableCategories() {
        assertEquals("unsupported", SsstikResolver.signalCategory("sssinvalidlink"));
        assertEquals("unavailable", SsstikResolver.signalCategory("sssterror"));
        assertEquals("rate", SsstikResolver.signalCategory("ssslimitexceed"));
        assertEquals("forbidden", SsstikResolver.signalCategory("sssblockedclient"));
        assertEquals("slides", SsstikResolver.signalCategory("ssssuccess_slides"));
        assertEquals("audio", SsstikResolver.signalCategory("ssssuccess_music"));
        assertEquals("watermark", SsstikResolver.signalCategory("ssssuccess_wmonly"));
        assertEquals("generic", SsstikResolver.signalCategory(null));
    }

    @Test public void prefersTheWatermarkFreeAnchorAndNeverTheMusicAnchor() {
        String fragment = "<div><a class=\"pure-button dl-button download_link music\" href=\"https://tikcdn.io/a/zzz\">MP3</a>"
                + "<a class=\"pure-button dl-button download_link without_watermark\" href=\"https://ssscdn.io/dl/vid1\">HD</a></div>";
        assertEquals("https://ssscdn.io/dl/vid1", SsstikResolver.parseVideoLink(fragment));
        assertNull(SsstikResolver.parseVideoLink("<a class=\"download_link music\" href=\"https://tikcdn.io/a/zzz\">MP3</a>"));
        assertNull(SsstikResolver.parseVideoLink("<p>no anchors here</p>"));
    }

    @Test public void readsTheResultHeadingAsTheTitle() {
        assertEquals("Micro drama EP 8", SsstikResolver.parseTitle("<h2 class=\"x\">Micro drama <span>EP 8</span></h2>"));
        assertEquals("", SsstikResolver.parseTitle("<p>nothing</p>"));
    }
}
