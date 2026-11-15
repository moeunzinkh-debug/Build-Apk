package com.xtr3.downloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** User-initiated VOD download. Partial files never enter the public Movies collection. */
public final class DownloadEngine {
    public interface Progress {
        void update(int phase, int percent, long bytes, long total, String detail);
    }
    public static final class Saved {
        public String id, name, uri, mime, note;
        public long size;
    }
    private static final int MAX_SEGMENT = 64 * 1024 * 1024;
    private static final long DIRECT_RANGE = 2L * 1024 * 1024;
    private final Context context;
    private final MediaCandidate candidate;
    private final Progress progress;
    private final BooleanSupplier cancelled;
    private final HttpMediaClient client = new HttpMediaClient();
    private final Map<String, byte[]> keys = new HashMap<>();
    private volatile HttpURLConnection current;
    private volatile PlatformEngine platform;

    public DownloadEngine(Context context, MediaCandidate candidate, Progress progress, BooleanSupplier cancelled) {
        this.context = context.getApplicationContext();
        this.candidate = candidate;
        this.progress = progress;
        this.cancelled = cancelled;
    }
    public void disconnect() {
        HttpURLConnection c = current; if (c != null) c.disconnect();
        PlatformEngine p = platform; if (p != null) p.cancel();
    }

    public Saved execute() throws IOException {
        String id = UUID.randomUUID().toString();
        File directory = new File(context.getCacheDir(), "downloads");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the temporary download folder.");
        File raw = new File(directory, id + ".part");
        File converted = new File(directory, id + ".mp4");
        File platformDirectory = new File(directory, id + "-platform");
        File publishedFile = null;
        Uri publishedUri = null;
        boolean committed = false;
        try {
            check();
            progress.update(R.string.phase_prepare, 1, 0, -1, "");
            boolean hls = MediaCandidate.HLS.equals(candidate.kind);
            boolean platformJob = MediaCandidate.PLATFORM.equals(candidate.kind);
            String mime = candidate.mime == null || candidate.mime.isEmpty() ? "video/mp4" : candidate.mime;
            String extension = LinkParser.extension(candidate.url, mime);
            String note = "";
            long estimate = candidate.size;
            if (platformJob) {
                requireSpace(estimate < 0 ? -1 : estimate * 3);
                platform = new PlatformEngine(context);
                PlatformEngine.Result result = platform.download(candidate, platformDirectory, progress, cancelled);
                publishedFile = result.file;
                candidate.title = result.title;
                note = result.note;
                mime = "video/mp4";
                extension = ".mp4";
            } else if (hls) {
                boolean selectedVariant = candidate.selectedQuality != null && candidate.selectedQuality.url != null;
                String hlsUrl = selectedVariant ? candidate.selectedQuality.url : candidate.url;
                HlsPlaylist playlist = loadPlaylist(hlsUrl, candidate.pageUrl, selectedVariant);
                boolean fragmented = playlist.fragmentedMp4();
                extension = fragmented ? ".mp4" : ".ts";
                mime = fragmented ? "video/mp4" : "video/mp2t";
                estimate = estimate > 0 ? estimate : playlist.duration > 0 ? (long) (playlist.duration * 625_000) : -1;
                requireSpace(estimate < 0 ? -1 : estimate * (fragmented ? 2 : 3));
                downloadPlaylist(playlist, raw, fragmented);
                if (!fragmented) {
                    check();
                    progress.update(R.string.phase_convert, 91, raw.length(), -1, context.getString(R.string.convert_detail));
                    if (TsRemuxer.convert(raw, converted, cancelled::getAsBoolean)) {
                        check();
                        publishedFile = converted;
                        extension = ".mp4";
                        mime = "video/mp4";
                        if (!raw.delete()) raw.deleteOnExit();
                    } else {
                        check();
                        publishedFile = raw;
                        note = context.getString(R.string.saved_as_ts);
                    }
                } else publishedFile = raw;
            } else {
                requireSpace(estimate < 0 ? -1 : estimate * 2);
                String[] type = downloadDirect(raw);
                mime = type[0];
                extension = type[1];
                publishedFile = raw;
            }
            check();
            if (publishedFile == null || publishedFile.length() == 0) throw new IOException("The server returned an empty video.");
            progress.update(R.string.phase_verify, 94, publishedFile.length(), -1, "");
            VideoProbe.Result verified = VideoProbe.inspect(publishedFile, mime);
            mime = verified.mime;
            extension = verified.extension;
            if (!verified.note.isEmpty()) note = note.isEmpty() ? verified.note : note + " · " + verified.note;
            String name = LinkParser.safeFilename(candidate.title) + "_" + System.currentTimeMillis() + extension;
            progress.update(R.string.phase_save, 96, publishedFile.length(), -1, "");
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, mime);
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Xtr3");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver resolver = context.getContentResolver();
            publishedUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (publishedUri == null) throw new IOException("Android could not create a file in Movies/Xtr3.");
            try (InputStream in = new FileInputStream(publishedFile); OutputStream out = resolver.openOutputStream(publishedUri, "w")) {
                if (out == null) throw new IOException("Android could not open the destination file.");
                byte[] buffer = new byte[64 * 1024];
                long copied = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    check();
                    out.write(buffer, 0, n);
                    copied += n;
                    progress.update(R.string.phase_save, 96 + (int) Math.min(3, copied * 3 / publishedFile.length()), copied, publishedFile.length(), "");
                }
                out.flush();
            }
            check();
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (resolver.update(publishedUri, done, null, null) != 1) throw new IOException("Android could not finish saving the video.");
            committed = true;
            Saved saved = new Saved();
            saved.id = id;
            saved.name = name;
            saved.uri = publishedUri.toString();
            saved.mime = mime;
            saved.size = publishedFile.length();
            saved.note = note;
            try (Cursor cursor = resolver.query(publishedUri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    saved.name = cursor.getString(0);
                    if (!cursor.isNull(1)) saved.size = cursor.getLong(1);
                }
            } catch (RuntimeException ignored) { }
            progress.update(R.string.phase_done, 100, saved.size, saved.size, note);
            return saved;
        } finally {
            if (!committed && publishedUri != null) {
                try { context.getContentResolver().delete(publishedUri, null, null); } catch (RuntimeException ignored) { }
            }
            if (!raw.delete()) raw.deleteOnExit();
            if (!converted.delete()) converted.deleteOnExit();
            deleteTree(platformDirectory);
            deleteInfoFile(candidate.platformInfoPath);
            platform = null;
            keys.clear();
            disconnect();
        }
    }

    private static void deleteTree(File root) {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!root.delete()) root.deleteOnExit();
    }

    private static void deleteInfoFile(String path) {
        if (path == null || path.isEmpty()) return;
        File file = new File(path);
        if (file.isFile() && !file.delete()) file.deleteOnExit();
    }

    private String[] downloadDirect(File output) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                check();
                if (attempt > 0) {
                    progress.update(R.string.phase_retry, 2, 0, -1, "");
                    Thread.sleep(800L * attempt);
                }
                // Mirror the player's bounded Range GETs; some CDNs reject an open-ended
                // full-file request even when the same URL plays in the WebView.
                connection = client.open(candidate.url, candidate.pageUrl, 0, DIRECT_RANGE, c -> current = c, candidate.headers);
                String mime = directMime(connection);
                long total = HttpMediaClient.length(connection);
                String name = HttpMediaClient.dispositionName(connection);
                if ((candidate.title == null || candidate.title.equals("Video")) && name != null) candidate.title = name;
                long count;
                try (RandomAccessFile out = new RandomAccessFile(output, "rw")) {
                    out.setLength(0);
                    count = 0;
                    while (true) {
                        check();
                        int code = connection.getResponseCode();
                        long before = count;
                        count = appendDirect(connection, out, count, total);
                        if (code == 200) {
                            // A server that ignores Range sent the whole file in one response.
                            if (total > 0 && count != total) throw new IOException("The connection ended before the whole video arrived.");
                            break;
                        }
                        if (total > 0) {
                            if (count >= total) break;
                            if (count == before) throw new IOException("The connection ended before the whole video arrived.");
                        } else {
                            // Rare unknown-length 206: finish with an open-ended range from
                            // the bytes already saved instead of guessing the file size.
                            connection.disconnect();
                            current = null;
                            connection = client.open(candidate.url, candidate.pageUrl, count, -1, c -> current = c, candidate.headers);
                            total = HttpMediaClient.length(connection);
                            count = appendDirect(connection, out, count, total);
                            if (total > 0 && count != total) throw new IOException("The connection ended before the whole video arrived.");
                            break;
                        }
                        connection.disconnect();
                        current = null;
                        connection = client.open(candidate.url, candidate.pageUrl, count, DIRECT_RANGE, c -> current = c, candidate.headers);
                        long nextTotal = HttpMediaClient.length(connection);
                        if (nextTotal > 0) total = nextTotal;
                    }
                }
                if (count == 0) throw new IOException("The server returned an empty video.");
                return new String[]{mime, LinkParser.extension(candidate.url, mime)};
            } catch (IOException e) {
                last = e;
                if (cancelled.getAsBoolean()) throw e;
                // Expired links and HTTP errors need a fresh detection, not a blind retry.
                if (e.getMessage() != null && (e.getMessage().startsWith("HTTP ") || e.getMessage().contains("web page"))) throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Cancelled.");
            } finally {
                if (connection != null) connection.disconnect();
                current = null;
            }
        }
        throw last == null ? new IOException("Download failed.") : last;
    }

    private String directMime(HttpURLConnection connection) throws IOException {
        String mime = HttpMediaClient.mime(connection);
        if (mime.contains("html") || mime.contains("json") || mime.startsWith("text/")) {
            throw new IOException("The link returned a web page instead of a video. Open the page and detect the video again.");
        }
        if (!mime.isEmpty() && !mime.startsWith("video/") && !mime.equals("application/octet-stream")
                && !mime.equals("binary/octet-stream") && !LinkParser.isVideo(candidate.url, null)) {
            throw new IOException("The server returned an unsupported file type: " + mime);
        }
        if (mime.isEmpty() || mime.equals("application/octet-stream") || mime.equals("binary/octet-stream")) {
            mime = LinkParser.path(candidate.url).endsWith(".webm") ? "video/webm"
                    : LinkParser.path(candidate.url).endsWith(".mov") ? "video/quicktime" : "video/mp4";
        }
        return mime;
    }

    private long appendDirect(HttpURLConnection connection, RandomAccessFile out, long count, long total) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            long lastUpdate = 0;
            while ((n = in.read(buffer)) != -1) {
                check();
                out.write(buffer, 0, n);
                count += n;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastUpdate > 250) {
                    int percent = total > 0 ? (int) Math.min(90, count * 90 / total) : 5;
                    progress.update(R.string.phase_download, percent, count, total, "");
                    lastUpdate = now;
                }
            }
        }
        return count;
    }

    private HlsPlaylist loadPlaylist(String url, String referer, boolean selectedVariant) throws IOException {
        String next = url;
        for (int depth = 0; depth < 4; depth++) {
            check();
            HttpURLConnection connection = client.open(next, referer, -1, -1, c -> current = c, candidate.headers);
            try {
                String mime = HttpMediaClient.mime(connection);
                if (mime.contains("html")) throw new IOException("The playlist link expired. Open the page and detect the video again.");
                String text = client.readText(connection, 2 * 1024 * 1024);
                next = connection.getURL().toString();
                HlsPlaylist playlist = HlsPlaylist.parse(text, next);
                if (!playlist.master) return playlist;
                // The user already chose a variant. If that URL unexpectedly points at another
                // master playlist, stop honestly instead of silently switching to a different quality.
                if (selectedVariant) throw new IOException("quality");
                next = HlsPlaylist.bestVariant(playlist).url;
            } finally { connection.disconnect(); current = null; }
        }
        throw new IOException("Too many nested HLS playlists.");
    }

    private void downloadPlaylist(HlsPlaylist playlist, File output, boolean fragmented) throws IOException {
        long totalSegments = playlist.segments.size(), completed = 0;
        double duration = playlist.duration > 0 ? playlist.duration : totalSegments;
        double finishedDuration = 0;
        long downloaded = 0;
        try (RandomAccessFile out = new RandomAccessFile(output, "rw")) {
            out.setLength(0);
            if (fragmented) {
                byte[] init = fetch(playlist.mapUrl, candidate.pageUrl, -1, -1, true);
                if (playlist.mapKey != null && "AES-128".equals(playlist.mapKey.method)) {
                    init = decrypt(init, playlist.mapKey, playlist.mediaSequence);
                } else if (!playlist.segments.isEmpty() && playlist.segments.get(0).key != null
                        && "AES-128".equals(playlist.segments.get(0).key.method)) {
                    throw new IOException("This encrypted HLS stream declares its key after the initialization segment.");
                }
                if (!hasBox(init, "ftyp") && !hasBox(init, "styp")) throw new IOException("The HLS initialization segment is not valid MP4.");
                out.write(init);
                downloaded += init.length;
            }
            for (HlsPlaylist.Segment segment : playlist.segments) {
                check();
                long mark = out.getFilePointer();
                IOException last = null;
                boolean ok = false;
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        check();
                        if (attempt > 0) Thread.sleep(700L * attempt);
                        byte[] data = fetch(segment.url, candidate.pageUrl, segment.offset, segment.length, true);
                        if (segment.key != null && "AES-128".equals(segment.key.method)) data = decrypt(data, segment.key, segment.sequence);
                        if (data.length == 0) throw new IOException("An HLS segment was empty.");
                        if (fragmented && completed > 0 && !hasBox(data, "moof") && !hasBox(data, "styp") && !hasBox(data, "mdat")) {
                            throw new IOException("An HLS fragment is not valid MP4.");
                        }
                        out.write(data);
                        downloaded += data.length;
                        ok = true;
                        break;
                    } catch (IOException e) {
                        last = e;
                        out.seek(mark);
                        out.setLength(mark);
                        if (cancelled.getAsBoolean()) throw e;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Cancelled.");
                    }
                }
                if (!ok) throw last == null ? new IOException("A video segment failed.") : last;
                completed++;
                finishedDuration += playlist.duration > 0 ? segment.duration : 1;
                int percent = 5 + (int) Math.min(85, finishedDuration * 85 / duration);
                progress.update(R.string.phase_download, percent, downloaded, -1,
                        String.format(Locale.ROOT, "%d / %d", completed, totalSegments));
            }
        }
    }

    private byte[] fetch(String url, String referer, long offset, long length, boolean segment) throws IOException {
        HttpURLConnection connection = client.open(url, referer, offset, length, c -> current = c, candidate.headers);
        try {
            String mime = HttpMediaClient.mime(connection);
            if (mime.contains("html") || mime.contains("json")) throw new IOException("A media segment returned a web page. The link may have expired.");
            if (!segment) return client.readKey(connection);
            int limit = length > 0 && length <= MAX_SEGMENT ? (int) length : MAX_SEGMENT;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
                byte[] buffer = new byte[32 * 1024];
                int n, count = 0;
                while ((n = in.read(buffer, 0, Math.min(buffer.length, limit + 1 - count))) != -1) {
                    check();
                    count += n;
                    if (count > limit || count > MAX_SEGMENT) throw new IOException("An HLS segment is larger than this lightweight downloader supports.");
                    out.write(buffer, 0, n);
                }
                if (length > 0 && count != length) throw new IOException("An HLS byte-range segment was incomplete.");
                return out.toByteArray();
            }
        } finally { connection.disconnect(); current = null; }
    }

    private byte[] decrypt(byte[] data, HlsPlaylist.Key key, long sequence) throws IOException {
        if (data.length == 0 || data.length % 16 != 0) throw new IOException("Invalid encrypted HLS segment length.");
        try {
            byte[] secret = keys.get(key.url);
            if (secret == null) {
                secret = fetch(key.url, candidate.pageUrl, -1, -1, false);
                if (keys.size() >= 32) keys.clear();
                keys.put(key.url, secret);
            }
            byte[] iv = new byte[16];
            if (key.iv != null) {
                String hex = key.iv.startsWith("0x") || key.iv.startsWith("0X") ? key.iv.substring(2) : key.iv;
                for (int i = 0; i < 16; i++) iv[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            } else {
                for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (sequence >>> (8 * i));
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(data);
            int padding = plain[plain.length - 1] & 0xff;
            if (padding < 1 || padding > 16 || padding > plain.length) throw new IOException("Invalid AES-128 padding; the key may be wrong.");
            for (int i = plain.length - padding; i < plain.length; i++) {
                if ((plain[i] & 0xff) != padding) throw new IOException("Invalid AES-128 padding; the key may be wrong.");
            }
            byte[] result = new byte[plain.length - padding];
            System.arraycopy(plain, 0, result, 0, result.length);
            return result;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Could not decrypt this AES-128 segment.", e);
        }
    }

    private static boolean hasBox(byte[] data, String type) {
        if (data.length < 8) return false;
        int offset = 0;
        for (int box = 0; box < 16 && offset + 8 <= data.length; box++) {
            long size = ((long) (data[offset] & 255) << 24) | ((data[offset + 1] & 255) << 16)
                    | ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
            String name = new String(data, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (type.equals(name)) return true;
            if (size == 1 && offset + 16 <= data.length) {
                size = 0;
                for (int i = 8; i < 16; i++) size = (size << 8) | (data[i] & 255);
            }
            if (size < 8 || size > data.length - offset) return false;
            offset += (int) size;
        }
        return false;
    }

    private void requireSpace(long needed) throws IOException {
        long available = new StatFs(context.getCacheDir().getAbsolutePath()).getAvailableBytes();
        long minimum = needed > 0 ? needed + 64L * 1024 * 1024 : 256L * 1024 * 1024;
        if (available < minimum) throw new IOException("Not enough free storage. Free some space and try again.");
    }
    private void check() throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled.");
    }
}
