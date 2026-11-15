package com.xtr3.downloader;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class RequestContextTest {
    @Test public void captureKeepsOnlyReplaySafeHeaders() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("User-Agent", "desktop-chrome");
        raw.put("Referer", "https://xstree.com/vplay/1.html");
        raw.put("Origin", "https://xstree.com");
        raw.put("Accept", "*/*");
        raw.put("sec-ch-ua", "\"Chromium\";v=\"124\"");
        raw.put("Cookie", "session=secret");
        raw.put("Authorization", "Bearer secret");
        raw.put("Range", "bytes=0-1");
        raw.put("Accept-Encoding", "gzip");
        raw.put("Host", "cdn.test");
        raw.put("Connection", "keep-alive");
        raw.put("Content-Length", "10");
        Map<String, String> captured = RequestContext.capture(raw);
        assertEquals("desktop-chrome", captured.get("user-agent"));
        assertEquals("https://xstree.com", captured.get("origin"));
        assertEquals("*/*", captured.get("accept"));
        assertTrue(captured.containsKey("sec-ch-ua"));
        assertFalse(captured.containsKey("cookie"));
        assertFalse(captured.containsKey("authorization"));
        assertFalse(captured.containsKey("range"));
        assertFalse(captured.containsKey("accept-encoding"));
        assertFalse(captured.containsKey("host"));
        assertFalse(captured.containsKey("connection"));
        assertFalse(captured.containsKey("content-length"));
    }
    @Test public void captureDropsOversizedAndEmptyValues() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("Referer", "");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) big.append("abcdefgh");
        raw.put("Origin", big.toString());
        raw.put("Accept", "*/*");
        Map<String, String> captured = RequestContext.capture(raw);
        assertEquals(1, captured.size());
        assertEquals("*/*", captured.get("accept"));
    }
    @Test public void captureHandlesNull() {
        assertTrue(RequestContext.capture(null).isEmpty());
    }
    @Test public void captureDropsHeaderInjection() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("Referer", "https://page.test/\r\nX-Injected: yes");
        raw.put("Accept", "*/*");
        Map<String, String> captured = RequestContext.capture(raw);
        assertFalse(captured.containsKey("referer"));
        assertEquals("*/*", captured.get("accept"));
    }
    @Test public void replayReappliesAllowlist() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("cookie", "session=secret");
        raw.put("authorization", "Bearer secret");
        raw.put("user-agent", "player-ua");
        Map<String, String> replay = RequestContext.replay(raw, "https://page.test/");
        assertEquals("player-ua", replay.get("user-agent"));
        assertEquals("https://page.test/", replay.get("referer"));
        assertFalse(replay.containsKey("cookie"));
        assertFalse(replay.containsKey("authorization"));
    }
    @Test public void replayPrefersCapturedOverGenericReferer() {
        Map<String, String> captured = new LinkedHashMap<>();
        captured.put("referer", "https://player.test/embed");
        captured.put("user-agent", "player-ua");
        Map<String, String> replay = RequestContext.replay(captured, "https://page.test/");
        assertEquals("https://player.test/embed", replay.get("referer"));
        assertEquals("player-ua", replay.get("user-agent"));
    }
    @Test public void replayFallsBackToPageReferer() {
        Map<String, String> replay = RequestContext.replay(null, "https://page.test/");
        assertEquals("https://page.test/", replay.get("referer"));
        assertFalse(replay.containsKey("user-agent"));
    }
}
