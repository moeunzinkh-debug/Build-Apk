package com.xtr3.downloader;

import org.junit.Test;
import static org.junit.Assert.*;

public class HlsPlaylistTest {
    private static final String BASE = "https://cdn.test/v/master.m3u8";

    @Test public void choosesHighestBandwidth() {
        HlsPlaylist p = HlsPlaylist.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nhigh.m3u8\n", BASE);
        assertTrue(p.master);
        assertEquals("https://cdn.test/v/high.m3u8", HlsPlaylist.bestVariant(p).url);
        assertEquals("1280x720 · 2.5 Mbps", HlsPlaylist.bestVariant(p).label());
    }
    @Test public void parsesVodSegmentsAndDuration() {
        HlsPlaylist p = HlsPlaylist.parse("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:5.0,\na.ts\n#EXTINF:6.5,\nb.ts\n#EXT-X-ENDLIST\n", BASE);
        assertFalse(p.master);
        assertEquals(2, p.segments.size());
        assertEquals(10, p.segments.get(0).sequence);
        assertEquals(11, p.segments.get(1).sequence);
        assertEquals(11.5, p.duration, 0.001);
        assertFalse(p.fragmentedMp4());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsLive() {
        HlsPlaylist.parse("#EXTM3U\n#EXTINF:5,\na.ts\n", BASE);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsDrm() {
        HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n", BASE);
    }
    @Test public void parsesAes128() {
        HlsPlaylist p = HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x00000000000000000000000000000001\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n", BASE);
        assertEquals("AES-128", p.segments.get(0).key.method);
        assertEquals("https://cdn.test/v/key.bin", p.segments.get(0).key.url);
    }
    @Test public void parsesByteRanges() {
        HlsPlaylist p = HlsPlaylist.parse("#EXTM3U\n#EXTINF:5,\n#EXT-X-BYTERANGE:100@200\na.ts\n#EXTINF:5,\n#EXT-X-BYTERANGE:50\na.ts\n#EXT-X-ENDLIST\n", BASE);
        assertEquals(200, p.segments.get(0).offset);
        assertEquals(100, p.segments.get(0).length);
        assertEquals(300, p.segments.get(1).offset);
        assertEquals(50, p.segments.get(1).length);
    }
    @Test public void parsesFmp4Map() {
        HlsPlaylist p = HlsPlaylist.parse("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:5,\na.m4s\n#EXT-X-ENDLIST\n", BASE);
        assertTrue(p.fragmentedMp4());
        assertEquals("https://cdn.test/v/init.mp4", p.mapUrl);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsSeparateAudio() {
        HlsPlaylist.parse("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio.m3u8\"\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n", BASE);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidPlaylist() {
        HlsPlaylist.parse("<html>not a playlist</html>", BASE);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsChangingMaps() {
        HlsPlaylist.parse("#EXTM3U\n#EXT-X-MAP:URI=\"init1.mp4\"\n#EXTINF:5,\na.m4s\n#EXT-X-MAP:URI=\"init2.mp4\"\n#EXTINF:5,\nb.m4s\n#EXT-X-ENDLIST\n", BASE);
    }
}
