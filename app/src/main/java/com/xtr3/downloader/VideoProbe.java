package com.xtr3.downloader;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Rejects HTML/JSON/playlists masquerading as video and checks for a real video track when possible. */
public final class VideoProbe {
    public static final class Result {
        public final String mime;
        public final String extension;
        public final String note;
        Result(String mime, String extension, String note) {
            this.mime = mime;
            this.extension = extension;
            this.note = note;
        }
    }

    private VideoProbe() { }

    public static Result inspect(File file, String declaredMime) throws IOException {
        byte[] head = new byte[65536];
        int read;
        try (InputStream in = new FileInputStream(file)) { read = in.read(head); }
        if (read <= 0) throw new IOException("The server returned an empty video.");
        byte[] prefix = read == head.length ? head : java.util.Arrays.copyOf(head, read);
        if (looksLikeDocument(prefix)) {
            throw new IOException("The server returned a web page or playlist instead of a video file.");
        }
        String signature = signature(prefix, file.length());
        String mime = declaredMime == null ? "" : declaredMime.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        String extension;
        if ("mkv".equals(signature)) { mime = "video/x-matroska"; extension = ".mkv"; }
        else if ("webm".equals(signature)) { mime = "video/webm"; extension = ".webm"; }
        else if ("ts".equals(signature)) { mime = "video/mp2t"; extension = ".ts"; }
        else if ("flv".equals(signature)) { mime = "video/x-flv"; extension = ".flv"; }
        else if ("avi".equals(signature)) { mime = "video/x-msvideo"; extension = ".avi"; }
        else if ("mpeg".equals(signature)) { mime = "video/mpeg"; extension = ".mpg"; }
        else if ("mp4".equals(signature)) {
            if (!mime.equals("video/quicktime")) mime = "video/mp4";
            extension = mime.equals("video/quicktime") ? ".mov" : ".mp4";
        } else {
            if (!mime.startsWith("video/")) mime = "video/mp4";
            extension = LinkParser.extension(file.getName(), mime);
        }

        String note = "";
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            boolean video = false;
            int width = 0, height = 0;
            long durationUs = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String trackMime = format.getString(MediaFormat.KEY_MIME);
                if (trackMime != null && trackMime.startsWith("video/")) {
                    video = true;
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) width = format.getInteger(MediaFormat.KEY_WIDTH);
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT);
                    if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = format.getLong(MediaFormat.KEY_DURATION);
                }
            }
            if (video) {
                if (width > 0 && height > 0) note = width + "×" + height;
                if (durationUs > 0) {
                    long seconds = durationUs / 1_000_000;
                    String time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
                    note = note.isEmpty() ? time : note + " · " + time;
                }
            } else if (signature == null) {
                throw new IOException("The downloaded data does not contain a video track.");
            }
        } catch (IOException | RuntimeException e) {
            if (signature == null) throw new IOException("The downloaded data is not a playable video file.");
            note = note.isEmpty() ? "Video container detected; track metadata unavailable on this device." : note;
        } finally {
            extractor.release();
        }
        return new Result(mime, extension, note);
    }

    static boolean looksLikeDocument(byte[] data) {
        int i = 0;
        while (i < data.length && (data[i] == ' ' || data[i] == '\n' || data[i] == '\r' || data[i] == '\t' || data[i] == 0)) i++;
        if (i >= data.length) return false;
        String text = new String(data, i, Math.min(data.length - i, 512), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<head")
                || text.startsWith("<body") || text.startsWith("<script") || text.startsWith("<?xml")
                || text.startsWith("#extm3u") || text.startsWith("#extinf")
                || text.startsWith("{") || text.startsWith("[");
    }

    static String signature(byte[] data, long fileLength) {
        if (data.length >= 4 && (data[0] & 255) == 0x1a && data[1] == 0x45 && (data[2] & 255) == 0xdf && (data[3] & 255) == 0xa3) {
            // Matroska and WebM share the EBML magic; the DocType element near the head
            // distinguishes them. FFmpeg-merged platform downloads can legitimately be MKV.
            String head = new String(data, 0, Math.min(data.length, 256), StandardCharsets.US_ASCII);
            return head.contains("matroska") ? "mkv" : "webm";
        }
        if (data.length >= 3 && data[0] == 'F' && data[1] == 'L' && data[2] == 'V') return "flv";
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'A' && data[9] == 'V' && data[10] == 'I' && data[11] == ' ') return "avi";
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 1 && (data[3] & 255) >= 0xba) return "mpeg";
        if (data.length >= 1 && (data[0] & 255) == 0x47) {
            boolean ts = fileLength < 188 || (data.length > 188 && (data[188] & 255) == 0x47)
                    || (data.length > 192 && (data[192] & 255) == 0x47);
            if (ts) return "ts";
        }
        int offset = 0;
        for (int box = 0; box < 16 && offset + 8 <= data.length; box++) {
            long size = ((long) (data[offset] & 255) << 24) | ((data[offset + 1] & 255) << 16)
                    | ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
            String type = new String(data, offset + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("ftyp") || type.equals("styp") || type.equals("moov") || type.equals("mdat")
                    || type.equals("moof") || type.equals("free") || type.equals("skip") || type.equals("wide")) return "mp4";
            if (size == 1 && offset + 16 <= data.length) {
                size = 0;
                for (int i = 8; i < 16; i++) size = (size << 8) | (data[i] & 255);
            }
            if (size < 8 || size > fileLength - offset) return null;
            offset += (int) size;
        }
        return null;
    }
}
