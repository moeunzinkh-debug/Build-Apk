package com.xtr3.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps a user-started transfer alive while the app is in the background. */
public final class DownloadService extends Service {
    public static final String ACTION_START = "com.xtr3.downloader.START";
    public static final String ACTION_CANCEL = "com.xtr3.downloader.CANCEL";
    public static final String EXTRA_CANDIDATE = "candidate";
    private static final String CHANNEL = "downloads";
    private static final int NOTIFICATION = 33;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean cancel;
    private volatile DownloadEngine engine;
    private ExecutorService worker;
    private DownloadStore.State latest = new DownloadStore.State();

    @Override public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadExecutor();
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancel = true;
            DownloadEngine current = engine;
            if (current != null) current.disconnect();
            return START_NOT_STICKY;
        }
        if (running.get()) {
            startForeground(NOTIFICATION, notification(getString(R.string.phase_download), latest.progress, false, null, null));
            return START_NOT_STICKY;
        }
        MediaCandidate candidate = null;
        if (intent != null) {
            try { candidate = (MediaCandidate) intent.getSerializableExtra(EXTRA_CANDIDATE); }
            catch (RuntimeException ignored) { }
        }
        if (candidate == null || candidate.url == null) { stopSelf(); return START_NOT_STICKY; }
        try { candidate.url = LinkParser.normalize(candidate.url); }
        catch (IllegalArgumentException e) { stopSelf(); return START_NOT_STICKY; }
        cancel = false;
        running.set(true);
        DownloadStore.State initial = new DownloadStore.State();
        initial.active = true;
        initial.title = LinkParser.cleanTitle(candidate.title);
        initial.phase = R.string.phase_prepare;
        publish(initial);
        startForeground(NOTIFICATION, notification(getString(initial.phase), 1, false, null, null));
        final MediaCandidate job = candidate;
        worker.execute(() -> run(job));
        return START_NOT_STICKY;
    }

    private void run(MediaCandidate candidate) {
        DownloadStore.State result = new DownloadStore.State();
        result.title = LinkParser.cleanTitle(candidate.title);
        Notification finalNotification;
        try {
            engine = new DownloadEngine(this, candidate, (phase, percent, bytes, total, detail) -> {
                DownloadStore.State s = new DownloadStore.State();
                s.active = true;
                s.title = result.title;
                s.phase = phase;
                s.progress = percent;
                s.bytes = bytes;
                s.total = total;
                s.note = detail;
                publish(s);
                getSystemService(NotificationManager.class).notify(NOTIFICATION,
                        notification(getString(phase) + (detail.isEmpty() ? "" : " · " + detail), percent, false, null, null));
            }, () -> cancel);
            DownloadEngine.Saved saved = engine.execute();
            DownloadStore.Record record = new DownloadStore.Record();
            record.id = saved.id; record.name = saved.name; record.uri = saved.uri;
            record.mime = saved.mime; record.size = saved.size; record.time = System.currentTimeMillis(); record.note = saved.note;
            DownloadStore.add(this, record);
            result.finishedUri = saved.uri;
            result.finishedMime = saved.mime;
            result.note = saved.note;
            result.progress = 100;
            result.phase = R.string.phase_done;
            finalNotification = notification(getString(R.string.phase_done), 100, true, saved.uri, saved.mime);
        } catch (IOException | RuntimeException e) {
            result.phase = cancel ? R.string.phase_cancelled : R.string.phase_failed;
            result.error = cancel ? getString(R.string.phase_cancelled) : readable(e);
            result.progress = 0;
            finalNotification = notification(result.error, 0, true, null, null);
        } finally {
            engine = null;
            running.set(false);
        }
        result.active = false;
        publish(result);
        stopForeground(STOP_FOREGROUND_DETACH);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, finalNotification);
        stopSelf();
    }

    private void publish(DownloadStore.State state) {
        latest = state;
        DownloadStore.publish(state);
    }

    private Notification notification(String text, int percent, boolean done, String uri, String mime) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(!done)
                .setAutoCancel(done)
                .setContentIntent(pending);
        if (done) {
            builder.setTimeoutAfter(30_000);
            if (uri != null) {
                Intent play = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                builder.setContentIntent(PendingIntent.getActivity(this, 1, play, flags));
            }
        } else {
            builder.setProgress(100, Math.max(0, percent), percent <= 1);
            Intent cancelIntent = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
            builder.addAction(new Notification.Action.Builder(R.drawable.ic_close, getString(R.string.cancel),
                    PendingIntent.getService(this, 2, cancelIntent, flags)).build());
        }
        return builder.build();
    }

    private String readable(Exception e) {
        String message = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return getString(R.string.error_network);
        if (e instanceof java.net.SocketTimeoutException) return getString(R.string.error_timeout);
        if (message == null || message.trim().isEmpty()) return getString(R.string.error_generic);
        if (message.length() > 300) message = message.substring(0, 300);
        return message;
    }

    @Override public void onTimeout(int startId, int fgsType) {
        cancel = true;
        DownloadEngine current = engine;
        if (current != null) current.disconnect();
    }
    @Override public void onDestroy() {
        cancel = true;
        DownloadEngine current = engine;
        if (current != null) current.disconnect();
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
