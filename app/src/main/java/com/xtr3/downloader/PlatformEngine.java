package com.xtr3.downloader;

import android.content.Context;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Site-aware extraction and transfer for public, non-DRM videos. yt-dlp, Python,
 * QuickJS and FFmpeg are bundled; no cookies, browser profile, or account credentials are read.
 */
public final class PlatformEngine {
    public static final class Result {
        public final File file;
        public final String title;
        public final String note;
        Result(File file, String title, String note) {
            this.file = file;
            this.title = title;
            this.note = note;
        }
    }
    private static final String ENGINE_VERSION = "2026.10.26+ejs0.8.0";
    private static final String ENGINE_VERSION = "2026.10.26+ejs0.8.0";
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean ready;
    private final Context context;
    private volatile String processId;

    public PlatformEngine(Context context) { this.context = context.getApplicationContext(); }

    public MediaCandidate inspect(String url, BooleanSupplier cancelled) throws IOException {
        String normalized = SupportedSite.canonical(LinkParser.normalize(url));
        if (SupportedSite.isCollection(normalized)) throw new IOException("COLLECTION");
        if (SupportedSite.identify(normalized) == null) throw new IOException("UNSUPPORTED");
        init();
        check(cancelled);
        YoutubeDLRequest request = request(PlatformOptions.info(normalized));
        String id = begin();
        try {
            YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, id, false, null);
            check(cancelled);
            JSONObject info = new JSONObject(response.getOut());
            if (info.has("entries")) throw new IOException("COLLECTION");
            if (info.optBoolean("is_live", false) || info.optBoolean("has_drm", false)) throw new IOException("protected");
            String live = info.optString("live_status", "");
            if (live.equals("is_live") || live.equals("is_upcoming") || live.equals("post_live")) throw new IOException("live");
            MediaCandidate candidate = new MediaCandidate(normalized, normalized,
                    LinkParser.cleanTitle(info.optString("title", "Video")), MediaCandidate.PLATFORM);
            candidate.site = SupportedSite.identify(normalized);
            candidate.mime = "video/mp4";
            candidate.quality = quality(info);
            candidate.size = size(info);
            candidate.platformNote = info.optString("extractor_key", "");
            Diagnostics.log("SITE", SupportedSite.label(normalized) + " metadata: "
                    + candidate.quality + "; extractor=" + candidate.platformNote);
            return candidate;
        } catch (org.json.JSONException e) {
            Diagnostics.log("SITE", "Metadata JSON error: " + e.getClass().getSimpleName());
            throw new IOException("generic");
        } catch (YoutubeDL.CanceledException e) {
            throw new IOException("Cancelled.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled.");
        } catch (com.yausername.youtubedl_android.YoutubeDLException e) {
            throw failure(e);
        } catch (RuntimeException e) {
            Diagnostics.log("SITE", "Metadata engine error: " + e.getClass().getSimpleName());
            throw new IOException("generic");
        } finally { end(id); }
    }

    public Result download(MediaCandidate candidate, File directory, DownloadEngine.Progress progress,
                           BooleanSupplier cancelled) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the temporary download folder.");
        String url = SupportedSite.canonical(LinkParser.normalize(candidate.url));
        init();
        check(cancelled);
        YoutubeDLRequest request = request(PlatformOptions.download(url, directory.getAbsolutePath()));
        String id = begin();
        final long[] lastPublish = {0};
        try {
            YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, id, false, (percent, eta, line) -> {
                if (cancelled.getAsBoolean()) { YoutubeDL.getInstance().destroyProcessById(id); return kotlin.Unit.INSTANCE; }
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastPublish[0] < 400 && percent < 99) return kotlin.Unit.INSTANCE;
                lastPublish[0] = now;
                int phase = line != null && (line.contains("[Merger]") || line.contains("[VideoConvertor]")
                        || line.contains("[Fixup") || line.contains("[MoveFiles]"))
                        ? R.string.phase_convert : R.string.phase_download;
                int value = percent < 0 ? 2 : Math.max(2, Math.min(90, 2 + (int) (percent * 0.88f)));
                progress.update(phase, value, -1, -1, "");
                return kotlin.Unit.INSTANCE;
            });
            check(cancelled);
            File file = output(directory, response.getOut());
            if (file == null || !file.isFile() || file.length() == 0) throw new IOException("generic");
            String title = LinkParser.cleanTitle(candidate.title);
            Diagnostics.log("SITE", "Platform transfer complete: " + file.length() + " bytes");
            return new Result(file, title, candidate.platformNote == null ? "" : candidate.platformNote);
        } catch (YoutubeDL.CanceledException e) {
            throw new IOException("Cancelled.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled.");
        } catch (com.yausername.youtubedl_android.YoutubeDLException e) {
            throw failure(e);
        } catch (RuntimeException e) {
            Diagnostics.log("SITE", "Transfer engine error: " + e.getClass().getSimpleName());
            throw new IOException("generic");
        } finally { end(id); }
    }

    public void cancel() {
        String id = processId;
        if (id != null) YoutubeDL.getInstance().destroyProcessById(id);
    }

    private void init() throws IOException {
        if (ready) return;
        synchronized (INIT_LOCK) {
            if (ready) return;
            try {
                YoutubeDL.getInstance().init(context);
                FFmpeg.getInstance().init(context);
                installEngine();
                ready = true;
                Diagnostics.log("SITE", "Bundled yt-dlp " + ENGINE_VERSION + " + Python/QuickJS/FFmpeg ready");
            } catch (Exception | LinkageError e) {
                Diagnostics.log("SITE", "Engine initialization failed: " + e.getClass().getSimpleName());
                throw new IOException("engine");
            }
        }
    }

    /** Force the audited raw-resource engine even if an older library copy was already unpacked. */
    private void installEngine() throws IOException {
        File dir = new File(new File(context.getNoBackupFilesDir(), "youtubedl-android"), "yt-dlp");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create engine directory.");
        File target = new File(dir, "yt-dlp");
        File marker = new File(dir, "xtr3-version");
        String previous = "";
        if (marker.isFile()) {
            try (InputStream in = new java.io.FileInputStream(marker)) {
                byte[] buffer = new byte[128];
                int n = in.read(buffer);
                if (n > 0) previous = new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) { }
        }
        if (ENGINE_VERSION.equals(previous) && target.isFile() && target.length() > 0) return;
        File temp = new File(dir, "yt-dlp.tmp");
        try (InputStream in = context.getResources().openRawResource(R.raw.ytdlp);
             OutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.flush();
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace old engine.");
        if (!temp.renameTo(target)) throw new IOException("Cannot install engine.");
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(ENGINE_VERSION.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static YoutubeDLRequest request(List<String> args) {
        // YoutubeDLRequest puts options before the URL. The final argument is the normalized URL.
        YoutubeDLRequest request = new YoutubeDLRequest(args.get(args.size() - 1));
        for (int i = 0; i < args.size() - 1; i++) {
            String option = args.get(i);
            if (option.startsWith("--") && i + 1 < args.size() - 1 && !args.get(i + 1).startsWith("--")) {
                request.addOption(option, args.get(++i));
            } else request.addOption(option);
        }
        return request;
    }

    private String begin() {
        String id = "xtr3_" + UUID.randomUUID().toString().replace("-", "");
        processId = id;
        return id;
    }
    private void end(String id) {
        YoutubeDL.getInstance().destroyProcessById(id);
        if (id.equals(processId)) processId = null;
    }
    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled.");
    }
    private static IOException failure(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        Diagnostics.log("SITE", "yt-dlp: " + LogSanitizer.redact(message.length() > 1200 ? message.substring(0, 1200) : message));
        return new IOException(PlatformOptions.errorCategory(message));
    }
    private static File output(File directory, String stdout) {
        if (stdout != null) {
            String[] lines = stdout.split("\\r?\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                File file = new File(line);
                if (file.isFile() && inside(directory, file)) return file;
            }
        }
        File[] files = directory.listFiles();
        if (files == null) return null;
        File best = null;
        for (File file : files) {
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (!file.isFile() || name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".json")) continue;
            if (best == null || file.length() > best.length()) best = file;
        }
        return best;
    }
    private static boolean inside(File directory, File file) {
        try { return file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator); }
        catch (IOException e) { return false; }
    }
    private static String quality(JSONObject info) {
        int height = info.optInt("height", 0);
        JSONArray formats = info.optJSONArray("requested_formats");
        if (formats == null) formats = info.optJSONArray("formats");
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.optJSONObject(i);
                if (f != null) height = Math.max(height, f.optInt("height", 0));
            }
        }
        return height > 0 ? height + "p" : "Best available";
    }
    private static long size(JSONObject info) {
        long size = info.optLong("filesize", -1);
        if (size <= 0) size = info.optLong("filesize_approx", -1);
        JSONArray formats = info.optJSONArray("requested_formats");
        if (size <= 0 && formats != null) {
            long sum = 0;
            boolean known = false;
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.optJSONObject(i);
                if (f == null) continue;
                long n = f.optLong("filesize", -1);
                if (n <= 0) n = f.optLong("filesize_approx", -1);
                if (n > 0) { sum += n; known = true; }
            }
            if (known) size = sum;
        }
        return size > 0 ? size : -1;
    }
}
