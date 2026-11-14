package com.xtr3.downloader;

import org.junit.Test;
import static org.junit.Assert.*;

public class LogSanitizerTest {
    @Test public void redactsXstreeRequestProofQueryFields() {
        String input = "GET https://120.76.173.51/user/info?cmd=app&rpv=1&rpt=1794663755&rpn=abc&rpk=secret&rpc=code&rpf=proof&rps=sig";
        String out = LogSanitizer.redact(input);
        assertTrue(out.contains("cmd=app"));
        assertTrue(out.contains("rpt=1794663755"));
        assertFalse(out.contains("rpn=abc"));
        assertFalse(out.contains("rpk=secret"));
        assertTrue(out.contains("rpk=[redacted]"));
    }
    @Test public void redactsJsonTokensAndBearerHeaders() {
        String out = LogSanitizer.redact("{\"token\":\"abc123\",\"url\":\"https://cdn.test/a.mp4\"} Authorization: Bearer xyz.abc");
        assertFalse(out.contains("abc123"));
        assertFalse(out.contains("xyz.abc"));
        assertTrue(out.contains("https://cdn.test/a.mp4"));
    }
    @Test public void keepsOrdinaryMediaUrls() {
        String input = "https://cdn.test/master.m3u8?quality=720";
        assertEquals(input, LogSanitizer.redact(input));
    }
}
