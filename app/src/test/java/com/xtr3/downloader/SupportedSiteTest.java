package com.xtr3.downloader;

import org.junit.Test;
import static org.junit.Assert.*;

public class SupportedSiteTest {
    @Test public void recognizesRequestedPlatformsAndShortLinks() {
        assertEquals(SupportedSite.BILIBILI, SupportedSite.identify("https://b23.tv/TAcxhVF"));
        assertEquals(SupportedSite.BILIBILI, SupportedSite.identify("https://www.bilibili.com/video/BV14MWC6FEog/"));
        assertEquals(SupportedSite.BILIBILI, SupportedSite.identify("https://www.bilibili.tv/en/video/123"));
        assertEquals(SupportedSite.FACEBOOK, SupportedSite.identify("https://fb.watch/abc/"));
        assertEquals(SupportedSite.FACEBOOK, SupportedSite.identify("https://www.facebook.com/reel/123"));
        assertEquals(SupportedSite.YOUTUBE, SupportedSite.identify("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals(SupportedSite.YOUTUBE, SupportedSite.identify("https://m.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals(SupportedSite.TIKTOK, SupportedSite.identify("https://vm.tiktok.com/abc/"));
        assertEquals(SupportedSite.TIKTOK, SupportedSite.identify("https://www.tiktok.com/@user/video/123"));
    }
    @Test public void doesNotRouteUnrelatedOrLookalikeHosts() {
        assertNull(SupportedSite.identify("https://xstree.com/vplay/1.html"));
        assertNull(SupportedSite.identify("https://youtube.com.example.com/watch?v=dQw4w9WgXcQ"));
        assertNull(SupportedSite.identify("https://notbilibili.com/video/BV1"));
        assertNull(SupportedSite.identify("https://example.com/?next=https://youtu.be/dQw4w9WgXcQ"));
    }
    @Test public void canonicalizesBilibiliInternationalShellWithoutLowercasingId() {
        assertEquals("https://www.bilibili.com/video/BV14MWC6FEog/?is_story_h5=false",
                SupportedSite.canonical("https://www.bilibili.com/en/video/BV14MWC6FEog/?is_story_h5=false"));
        assertEquals("https://www.bilibili.com/video/BV1AbC_def",
                SupportedSite.canonical("https://www.bilibili.com/zh-CN/video/BV1AbC_def"));
    }
    @Test public void canonicalizesYoutubeShortsLiveEmbedAndShortLink() {
        String watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        assertEquals(watch, SupportedSite.canonical("https://youtu.be/dQw4w9WgXcQ?t=10"));
        assertEquals(watch, SupportedSite.canonical("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals(watch, SupportedSite.canonical("https://www.youtube.com/live/dQw4w9WgXcQ"));
        assertEquals(watch, SupportedSite.canonical("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"));
    }
    @Test public void recognizesTheMicroDramaEntryPointAndTheAlternateShortHost() {
        assertEquals(SupportedSite.TIKTOK, SupportedSite.identify("https://vt.tiktok.com/ZSpqSc5Rq/"));
        assertEquals(SupportedSite.TIKTOK, SupportedSite.identify("https://www.xzcs3zlph.com/t/abc/"));
        assertTrue(SupportedSite.isShortDrama("https://www.tiktok.com/shortdrama/episode/7675706520839033876/8"));
        assertFalse(SupportedSite.isShortDrama("https://www.tiktok.com/@user/video/7675708755559959821"));
        assertFalse(SupportedSite.isShortDrama("https://youtu.be/dQw4w9WgXcQ"));
    }
    @Test public void onlyExpandsShortenersTheEngineCannotResolveItself() {
        assertTrue(SupportedSite.needsExpansion("https://b23.tv/TAcxhVF"));
        assertTrue(SupportedSite.needsExpansion("https://fb.watch/abc/"));
        // TikTok vm/vt/t and youtu.be are handled natively; expanding them wastes a round trip.
        assertFalse(SupportedSite.needsExpansion("https://vt.tiktok.com/ZSpqSc5Rq/"));
        assertFalse(SupportedSite.needsExpansion("https://vm.tiktok.com/abc/"));
        assertFalse(SupportedSite.needsExpansion("https://youtu.be/dQw4w9WgXcQ"));
    }
    @Test public void treatsLoginAndVerificationRedirectsAsInterstitialsNotVideos() {
        assertTrue(SupportedSite.isInterstitial("https://www.tiktok.com/login?redirect_url=x"));
        assertTrue(SupportedSite.isInterstitial("https://www.tiktok.com/verify/captcha"));
        assertFalse(SupportedSite.isInterstitial("https://www.tiktok.com/@user/video/123"));
    }
    @Test public void tikTokDramaNeedsAnEpisodeLink() {
        assertTrue(SupportedSite.isCollection("https://www.tiktok.com/@user/collection/drama-123"));
        assertTrue(SupportedSite.isCollection("https://www.tiktok.com/@user/series/123"));
        assertFalse(SupportedSite.isCollection("https://www.tiktok.com/@user/video/123"));
        assertFalse(SupportedSite.isCollection("https://youtu.be/dQw4w9WgXcQ"));
    }
}
