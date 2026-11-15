package com.xtr3.downloader;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PlatformOptionsTest {
    @Test public void infoIsReadOnlyAndDoesNotUseCredentialsOrRemoteCode() {
        List<String> args = PlatformOptions.info("https://youtu.be/dQw4w9WgXcQ");
        assertTrue(args.contains("--dump-single-json"));
        assertTrue(args.contains("--skip-download"));
        assertTrue(args.contains("--no-cookies"));
        assertTrue(args.contains("--no-cookies-from-browser"));
        assertTrue(args.contains("--netrc-location"));
        assertTrue(args.contains("/dev/null"));
        assertTrue(args.contains("--no-remote-components"));
        assertTrue(args.contains("--no-plugin-dirs"));
        assertTrue(args.contains("--ignore-config"));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", args.get(args.size() - 1));
    }
    @Test public void downloadMergesAudioVideoAndRejectsLiveDrm() {
        List<String> args = PlatformOptions.download("https://www.bilibili.com/video/BV1", "/data/cache/job");
        assertTrue(args.contains("--merge-output-format"));
        assertTrue(args.contains("mp4/mkv"));
        assertTrue(args.contains("--print"));
        assertTrue(args.contains("after_move:filepath"));
        assertTrue(args.contains("--match-filters"));
        assertTrue(args.contains("!is_live & !has_drm"));

        assertTrue(args.contains("--max-filesize"));
        assertTrue(args.contains(PlatformOptions.MAX_FILE_SIZE));
        assertTrue(args.contains("/data/cache/job"));
        assertEquals("https://www.bilibili.com/video/BV1", args.get(args.size() - 1));
    }
    @Test public void refusesCollectionsAndNonPlatformLinks() {
        assertThrows(IllegalArgumentException.class, () -> PlatformOptions.info("https://www.tiktok.com/@u/collection/drama-1"));
        assertThrows(IllegalArgumentException.class, () -> PlatformOptions.info("https://xstree.com/vplay/1.html"));
    }
    @Test public void mapsSensitiveAccessFailuresWithoutBypassingThem() {
        assertEquals("protected", PlatformOptions.errorCategory("This format is DRM protected"));
        assertEquals("paid", PlatformOptions.errorCategory("Join this channel to get access to members-only content"));
        assertEquals("login", PlatformOptions.errorCategory("Sign in to confirm your age"));
        assertEquals("forbidden", PlatformOptions.errorCategory("HTTP Error 403: Forbidden"));
        assertEquals("rate", PlatformOptions.errorCategory("HTTP Error 429: Too Many Requests"));
        assertEquals("network", PlatformOptions.errorCategory("TLS/SSL connection has been closed"));
        assertEquals("generic", PlatformOptions.errorCategory("something else"));
    }
}
