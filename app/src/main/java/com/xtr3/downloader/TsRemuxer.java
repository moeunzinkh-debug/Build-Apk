package com.xtr3.downloader;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Lossless TS-to-MP4 packaging using Android's built-in codecs; no bundled FFmpeg. */
public final class TsRemuxer {
    private TsRemuxer() { }

    public static boolean convert(File source, File destination, BooleanSupplier cancelled) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false, success = false;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            Map<Integer, Integer> tracks = new HashMap<>();
            muxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            boolean video = false, audio = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                boolean isVideo = mime.startsWith("video/"), isAudio = mime.startsWith("audio/");
                if ((isVideo && !video) || (isAudio && !audio)) {
                    if (mime.equals("audio/mpeg") || mime.equals("video/mp4v-es")) continue;
                    tracks.put(i, muxer.addTrack(format));
                    extractor.selectTrack(i);
                    video |= isVideo;
                    audio |= isAudio;
                    if (isVideo && format.containsKey(MediaFormat.KEY_ROTATION)) {
                        muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION));
                    }
                }
            }
            if (!video || tracks.isEmpty()) return false;
            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long samples = 0;
            while (!cancelled.getAsBoolean()) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) { success = true; break; }
                Integer output = tracks.get(track);
                if (output == null) { extractor.advance(); continue; }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) { extractor.advance(); continue; }
                info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags());
                muxer.writeSampleData(output, buffer, info);
                extractor.advance();
                if (++samples % 128 == 0 && cancelled.getAsBoolean()) break;
            }
            if (success) { muxer.stop(); started = false; }
            return success && destination.length() > 0;
        } catch (RuntimeException | java.io.IOException ignored) {
            // Codec/container combinations differ by device. Keep the valid TS instead.
            return false;
        } finally {
            if (muxer != null) {
                try { if (started) muxer.stop(); } catch (RuntimeException ignored) { }
                try { muxer.release(); } catch (RuntimeException ignored) { }
            }
            extractor.release();
            if (!success && !destination.delete()) destination.deleteOnExit();
        }
    }
}
