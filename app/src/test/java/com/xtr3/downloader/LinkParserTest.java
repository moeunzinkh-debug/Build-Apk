package com.xtr3.downloader;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LinkParserTest {
    private static final String PAGE = "https://xstree.com/vplay/35835512.html";

    @Test public void normalizesExample() {
        assertEquals(PAGE, LinkParser.normalize("  " + PAGE + "  "));
        assertEquals(PAGE, LinkParser.normalize("xstree.com/vplay/35835512.html"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsJavascript() {
        LinkParser.normalize("javascript:alert(1)");
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsFile() {
        LinkParser.normalize("file:///sdcard/movie.mp4");
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsCredentials() {
        LinkParser.normalize("https://user:secret@example.com/a.mp4");
    }
    @Test public void resolvesRelativeAndProtocolRelative() {
        assertEquals("https://cdn.example.com/a.mp4", LinkParser.resolve(PAGE, "//cdn.example.com/a.mp4"));
        assertEquals("https://xstree.com/media/a.mp4", LinkParser.resolve(PAGE, "../media/a.mp4"));
    }
    @Test public void rejectsNonWebResolution() {
        assertNull(LinkParser.resolve(PAGE, "blob:https://example.com/id"));
        assertNull(LinkParser.resolve(PAGE, "data:video/mp4;base64,AAAA"));
    }
    @Test public void extractsVideoAndSource() {
        List<MediaCandidate> list = LinkParser.extract("<title>My clip</title><video src='/a.mp4'></video><source src='https://cdn.test/b.m3u8' type='application/x-mpegURL'>", PAGE);
        assertEquals(2, list.size());
        assertEquals("https://xstree.com/a.mp4", list.get(0).url);
        assertEquals(MediaCandidate.HLS, list.get(1).kind);
        assertEquals("My clip", list.get(0).title);
    }
    @Test public void extractsEscapedJsonAndEntities() {
        List<MediaCandidate> list = LinkParser.extract("{\"video_url\":\"https:\\/\\/cdn.test\\/v.mp4?token=1&amp;quality=720\"}", PAGE);
        assertEquals(1, list.size());
        assertEquals("https://cdn.test/v.mp4?token=1&quality=720", list.get(0).url);
    }
    @Test public void extractsOpenGraph() {
        List<MediaCandidate> list = LinkParser.extract("<meta property='og:video:secure_url' content='https://cdn.test/og.mp4'>", PAGE);
        assertEquals(1, list.size());
    }
    @Test public void deduplicatesAndIgnoresImages() {
        List<MediaCandidate> list = LinkParser.extract("<video src='https://cdn.test/a.mp4'></video><a href='https://cdn.test/a.mp4'>same</a><img src='https://cdn.test/a.jpg'>", PAGE);
        assertEquals(1, list.size());
    }
    @Test public void recognizesQueryMediaUrls() {
        assertTrue(LinkParser.isVideo("https://cdn.test/video.mp4?expires=123", null));
        assertTrue(LinkParser.isHls("https://cdn.test/stream", "application/vnd.apple.mpegurl"));
        assertFalse(LinkParser.isVideo("https://cdn.test/page.html", "text/html"));
    }
    @Test public void detectsQueryHlsAndBrowserHints() {
        assertTrue(LinkParser.isHls("https://cdn.test/play?file=master.m3u8", null));
        assertTrue(LinkParser.isProbablyMedia("https://cdn.test/videoplayback?id=1", null));
        assertTrue(LinkParser.isProbablyMedia("https://cdn.test/api?mime=video/mp4", null));
        assertFalse(LinkParser.isProbablyMedia("https://cdn.test/page.html", "text/html"));
    }
    @Test public void identifiesFragments() {
        assertTrue(LinkParser.isFragment("https://cdn.test/seg001.ts"));
        assertTrue(LinkParser.isFragment("https://cdn.test/seg001.m4s"));
        assertFalse(LinkParser.isFragment("https://cdn.test/master.m3u8"));
    }
    @Test public void keepsTransportStreamExtension() {
        assertEquals(".ts", LinkParser.extension("https://cdn.test/video.ts", "video/mp2t"));
    }
    @Test public void sanitizesPathTraversal() {
        String name = LinkParser.safeFilename("../../a/b:c*?\"<>|");
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
        assertFalse(name.contains(".."));
    }
    @Test public void unicodeJsonEscapes() {
        assertEquals("a&b/ន", LinkParser.jsonUnescape("a\\u0026b\\u002f\\u1793"));
    }
    @Test public void attributeParserHandlesSingleAndDoubleQuotes() {
        assertEquals("a&b", LinkParser.attributes("src=\"a&amp;b\"").get("src"));
        assertEquals("720p", LinkParser.attributes("label='720p'").get("label"));
    }
}
