package com.xtr3.downloader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final int REQUEST_BROWSER = 41;
    private static final int REQUEST_NOTIFICATIONS = 42;
    private static final Pattern URL_IN_TEXT = Pattern.compile("(?:https?://|www\\.)[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final DownloadStore.Listener listener = state -> main.post(() -> renderState(state));
    private EditText input;
    private TextView status;
    private ProgressBar analysisProgress;
    private LinearLayout results, linkPanel, libraryPanel, libraryContainer, activeCard;
    private Button analyzeButton, newTab, libraryTab, activeOpen, activeCancel;
    private TextView activeTitle, activeStatus, activeDetail, libraryEmpty;
    private ProgressBar activeProgress;
    private volatile MediaResolver resolver;
    private volatile boolean busy;
    private int generation;
    private MediaCandidate pendingPermissionCandidate;
    private boolean dismissedResult;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.insets(this, findViewById(R.id.root_scroll));
        Diagnostics.init(this);
        input = findViewById(R.id.url_input);
        status = findViewById(R.id.status_text);
        analysisProgress = findViewById(R.id.analysis_progress);
        results = findViewById(R.id.results_container);
        linkPanel = findViewById(R.id.link_panel);
        libraryPanel = findViewById(R.id.library_panel);
        libraryContainer = findViewById(R.id.library_container);
        libraryEmpty = findViewById(R.id.library_empty);
        analyzeButton = findViewById(R.id.analyze_button);
        newTab = findViewById(R.id.tab_new);
        libraryTab = findViewById(R.id.tab_library);
        activeCard = findViewById(R.id.active_card);
        activeTitle = findViewById(R.id.active_title);
        activeStatus = findViewById(R.id.active_status);
        activeDetail = findViewById(R.id.active_detail);
        activeProgress = findViewById(R.id.active_progress);
        activeOpen = findViewById(R.id.active_open);
        activeCancel = findViewById(R.id.active_cancel);
        newTab.setOnClickListener(v -> showTab(true));
        libraryTab.setOnClickListener(v -> showTab(false));
        analyzeButton.setOnClickListener(v -> { if (busy) cancelAnalysis(); else analyze(); });
        findViewById(R.id.browser_button).setOnClickListener(v -> openBrowser());
        findViewById(R.id.developer_button).setOnClickListener(v ->
                startActivity(new Intent(this, DeveloperActivity.class).putExtra("url", input.getText().toString())));
        findViewById(R.id.clear_button).setOnClickListener(v -> {
            cancelAnalysis(); input.setText(""); results.removeAllViews(); status.setText(R.string.ready);
        });
        findViewById(R.id.paste_button).setOnClickListener(v -> paste());
        findViewById(R.id.example_button).setOnClickListener(v -> {
            input.setText(R.string.example_url); input.setSelection(input.length()); showTab(true);
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) { analyze(); return true; }
            return false;
        });
        activeCancel.setOnClickListener(v -> {
            if (DownloadStore.state().active) {
                startService(new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_CANCEL));
            } else { dismissedResult = true; activeCard.setVisibility(View.GONE); }
        });
        activeOpen.setOnClickListener(v -> {
            DownloadStore.State s = DownloadStore.state();
            if (s.finishedUri != null) Ui.openVideo(this, s.finishedUri, s.finishedMime);
        });
        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String value = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) value = firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT));
        else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) value = intent.getDataString();
        if (value != null) {
            input.setText(value);
            showTab(true);
            analyze();
        }
    }
    private static String firstUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_IN_TEXT.matcher(text);
        return m.find() ? m.group().replaceAll("[),.;]+$", "") : null;
    }
    private void paste() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard.getPrimaryClip();
        String value = clip != null && clip.getItemCount() > 0 ? firstUrl(String.valueOf(clip.getItemAt(0).coerceToText(this))) : null;
        if (value == null) { Ui.toast(this, R.string.clipboard_empty); return; }
        input.setText(value);
        input.setSelection(input.length());
    }
    private void showTab(boolean isNew) {
        linkPanel.setVisibility(isNew ? View.VISIBLE : View.GONE);
        libraryPanel.setVisibility(isNew ? View.GONE : View.VISIBLE);
        newTab.setBackgroundResource(isNew ? R.drawable.bg_primary : R.drawable.bg_secondary);
        newTab.setTextColor(getColor(isNew ? R.color.button_text : R.color.text));
        libraryTab.setBackgroundResource(isNew ? R.drawable.bg_secondary : R.drawable.bg_primary);
        libraryTab.setTextColor(getColor(isNew ? R.color.text : R.color.button_text));
        if (!isNew) renderLibrary();
    }

    private String normalizedInput() {
        try { return LinkParser.normalize(input.getText().toString()); }
        catch (IllegalArgumentException e) { status.setText(R.string.url_invalid); input.requestFocus(); return null; }
    }
    private void analyze() {
        if (busy) return;
        String url = normalizedInput();
        if (url == null) return;
        showTab(true);
        cancelAnalysis();
        int token = ++generation;
        busy = true;
        results.removeAllViews();
        status.setText(R.string.analysing);
        analysisProgress.setVisibility(View.VISIBLE);
        analyzeButton.setText(R.string.stop_analysis);
        MediaResolver current = new MediaResolver();
        resolver = current;
        worker.execute(() -> {
            try {
                MediaResolver.Result result = current.resolve(url);
                main.post(() -> { if (token == generation) { finishAnalysis(); renderCandidates(result.candidates, result.needsBrowser, url); } });
            } catch (IOException | RuntimeException e) {
                String message = readable(e);
                main.post(() -> { if (token == generation) { finishAnalysis(); status.setText(message); offerBrowser(url); } });
            }
        });
    }
    private void cancelAnalysis() {
        generation++;
        MediaResolver current = resolver;
        if (current != null) current.cancel();
        if (busy) status.setText(R.string.analysis_cancelled);
        finishAnalysis();
    }
    private void finishAnalysis() {
        busy = false;
        resolver = null;
        analysisProgress.setVisibility(View.GONE);
        analyzeButton.setText(R.string.analyze);
    }
    private String readable(Exception e) {
        if (e instanceof java.net.UnknownHostException) return getString(R.string.error_network);
        if (e instanceof java.net.SocketTimeoutException) return getString(R.string.error_timeout);
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? getString(R.string.error_generic) : message;
    }

    private void renderCandidates(List<MediaCandidate> candidates, boolean needsBrowser, String page) {
        results.removeAllViews();
        if (needsBrowser || candidates.isEmpty()) {
            status.setText(R.string.no_media_body);
            offerBrowser(page);
            return;
        }
        status.setText(R.string.found_videos);
        for (MediaCandidate c : candidates) results.addView(candidateCard(c));
    }
    private LinearLayout candidateCard(MediaCandidate c) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, c.title == null ? getString(R.string.app_name) : c.title, 17, true));
        String format = format(c);
        String size = MediaCandidate.HLS.equals(c.kind) ? getString(R.string.save_location) : Ui.bytes(this, c.size);
        card.addView(Ui.text(this, getString(R.string.metadata_size, format, size), 13, false));
        TextView host = Ui.text(this, LinkParser.host(c.url), 12, false);
        host.setSingleLine(true);
        host.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(host);
        Button download = Ui.button(this, getString(R.string.download), true);
        download.setOnClickListener(v -> confirmDownload(c));
        card.addView(download);
        return card;
    }
    private String format(MediaCandidate c) {
        if (MediaCandidate.HLS.equals(c.kind)) {
            return c.quality == null || c.quality.isEmpty() || c.quality.equals("Best available")
                    ? getString(R.string.format_hls) : "HLS · " + c.quality;
        }
        String path = LinkParser.path(c.url);
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && path.length() - dot <= 6) return path.substring(dot + 1).toUpperCase(Locale.ROOT);
        return getString(R.string.format_direct);
    }
    private void offerBrowser(String page) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, getString(R.string.no_media_title), 17, true));
        card.addView(Ui.text(this, getString(R.string.no_media_body), 14, false));
        Button button = Ui.button(this, getString(R.string.open_browser), false);
        button.setOnClickListener(v -> openBrowser(page));
        card.addView(button);
        results.addView(card);
    }
    private void openBrowser() { String url = normalizedInput(); if (url != null) openBrowser(url); }
    private void openBrowser(String url) {
        cancelAnalysis();
        Intent intent = new Intent(this, BrowserActivity.class).putExtra("url", url);
        startActivityForResult(intent, REQUEST_BROWSER);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BROWSER && resultCode == RESULT_OK && data != null) {
            try {
                MediaCandidate c = (MediaCandidate) data.getSerializableExtra("candidate");
                if (c != null) {
                    input.setText(c.pageUrl == null ? c.url : c.pageUrl);
                    renderCandidates(java.util.Collections.singletonList(c), false, c.pageUrl);
                    confirmDownload(c);
                }
            } catch (RuntimeException e) { Ui.toast(this, R.string.error_generic); }
        }
    }
    private void confirmDownload(MediaCandidate c) {
        if (DownloadStore.state().active) { Ui.toast(this, R.string.already_downloading); return; }
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_confirm_title)
                .setMessage(getString(R.string.download_confirm_body, c.title, format(c), LinkParser.host(c.url)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.download, (d, w) -> requestDownload(c))
                .show();
    }
    private void requestDownload(MediaCandidate c) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("askedNotifications", false)) {
            pendingPermissionCandidate = c;
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else startDownload(c);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) Ui.toast(this, R.string.permission_hint);
            MediaCandidate c = pendingPermissionCandidate;
            pendingPermissionCandidate = null;
            if (c != null) startDownload(c);
        }
    }
    private void startDownload(MediaCandidate c) {
        if (DownloadStore.state().active) { Ui.toast(this, R.string.already_downloading); return; }
        dismissedResult = false;
        Intent intent = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_CANDIDATE, (Serializable) c);
        startForegroundService(intent);
        Ui.toast(this, R.string.download_started);
    }

    private void renderState(DownloadStore.State s) {
        if (!s.active && (dismissedResult || (s.finishedUri == null && s.error == null))) {
            activeCard.setVisibility(View.GONE); return;
        }
        activeCard.setVisibility(View.VISIBLE);
        activeTitle.setText(s.title);
        activeStatus.setText(s.error == null ? getString(s.phase) : getString(s.phase) + "\n" + s.error);
        activeStatus.setTextColor(getColor(s.error == null ? R.color.muted : R.color.danger));
        activeProgress.setVisibility(s.active ? View.VISIBLE : View.GONE);
        activeProgress.setIndeterminate(s.active && s.progress <= 1);
        activeProgress.setProgress(s.progress);
        activeCancel.setText(s.active ? R.string.cancel : R.string.close);
        activeOpen.setVisibility(s.finishedUri == null ? View.GONE : View.VISIBLE);
        if (s.finishedUri != null) {
            activeDetail.setText(s.note == null || s.note.isEmpty() ? getString(R.string.saved_hint) : s.note);
        } else if (s.active) {
            String amount = Ui.bytes(this, s.bytes) + (s.total > 0 ? " / " + Ui.bytes(this, s.total) : "");
            String detail = s.note == null || s.note.isEmpty() ? amount : s.note + " · " + amount;
            activeDetail.setText(getString(R.string.progress_detail, s.progress, detail));
        } else activeDetail.setText("");
        if (!s.active && s.finishedUri != null) renderLibrary();
    }

    private void renderLibrary() {
        libraryContainer.removeAllViews();
        List<DownloadStore.Record> records = DownloadStore.all(this);
        libraryEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        for (DownloadStore.Record r : records) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.text(this, r.name, 16, true));
            card.addView(Ui.text(this, getString(R.string.library_meta, Ui.bytes(this, r.size), Ui.date(r.time)), 12, false));
            if (r.note != null && !r.note.isEmpty()) card.addView(Ui.text(this, r.note, 12, false));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button open = Ui.button(this, getString(R.string.open), true);
            Button delete = Ui.button(this, getString(R.string.delete), false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = Ui.dp(this, 8);
            open.setLayoutParams(lp);
            delete.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            open.setOnClickListener(v -> Ui.openVideo(this, r.uri, r.mime));
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(R.string.delete).setMessage(R.string.delete_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d, w) -> worker.execute(() -> {
                        try {
                            int rows = getContentResolver().delete(Uri.parse(r.uri), null, null);
                            if (rows > 0) DownloadStore.remove(this, r.id);
                            main.post(() -> { Ui.toast(this, rows > 0 ? R.string.deleted : R.string.delete_failed); renderLibrary(); });
                        } catch (RuntimeException e) {
                            main.post(() -> Ui.toast(this, R.string.delete_failed));
                        }
                    })).show());
            row.addView(open); row.addView(delete); card.addView(row);
            libraryContainer.addView(card);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        DownloadStore.listen(listener);
        renderState(DownloadStore.state());
        renderLibrary();
    }
    @Override protected void onPause() {
        DownloadStore.unlisten(listener);
        super.onPause();
    }
    @Override public void onBackPressed() {
        if (busy) cancelAnalysis();
        else if (libraryPanel.getVisibility() == View.VISIBLE) showTab(true);
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        generation++;
        MediaResolver current = resolver;
        if (current != null) current.cancel();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
