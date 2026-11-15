package com.xtr3.downloader;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class VideoProbeTest {
    @Test public void detectsMp4Header() {
        byte[] data = new byte[32];
        data[3] = 32;
        System.arraycopy("ftypisom".getBytes(StandardCharsets.US_ASCII), 0, data, 4, 8);
        assertEquals("mp4", VideoProbe.signature(data, 1024));
    }
    @Test public void detectsFragmentedMp4Header() {
        byte[] data = new byte[24];
        data[3] = 24;
        System.arraycopy("stypmsdh".getBytes(StandardCharsets.US_ASCII), 0, data, 4, 8);
        assertEquals("mp4", VideoProbe.signature(data, 1024));
    }
    @Test public void detectsWebmHeader() {
        byte[] data = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 1, 2, 3};
        assertEquals("webm", VideoProbe.signature(data, 1024));
    }
    @Test public void detectsTransportStreamSync() {
        byte[] data = new byte[400];
        data[0] = 0x47;
        data[188] = 0x47;
        data[376] = 0x47;
        assertEquals("ts", VideoProbe.signature(data, 400));
    }
    @Test public void rejectsHtmlAndJsonDocuments() {
        assertTrue(VideoProbe.looksLikeDocument("  <!DOCTYPE html><html>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(VideoProbe.looksLikeDocument("{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(VideoProbe.looksLikeDocument("#EXTM3U\n#EXTINF:10,".getBytes(StandardCharsets.UTF_8)));
        assertFalse(VideoProbe.looksLikeDocument(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'}));
    }
    @Test public void unknownBinaryIsNotGivenASignature() {
        assertNull(VideoProbe.signature(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 8));
    }
}
