package com.xtr3.downloader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Shows why media was or was not detected, and enables chrome://inspect for debug builds. */
public final class DeveloperActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView logView, statusView;
    private ScrollView scroll;
    private Switch toggle, tikwmToggle, ssstikToggle;
    private String url;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { renderLog(); main.postDelayed(this, 1000); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer);
        Ui.insets(this, findViewById(R.id.developer_root));
        Diagnostics.init(this);
        url = getIntent().getStringExtra("url");
        toggle = findViewById(R.id.developer_switch);
        statusView = findViewById(R.id.developer_status);
        logView = findViewById(R.id.developer_log);
        scroll = findViewById(R.id.developer_scroll);
        logView.setMovementMethod(new ScrollingMovementMethod());
        toggle.setChecked(Diagnostics.isEnabled(this));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            Diagnostics.setEnabled(this, checked);
            updateStatus();
            if (checked) Ui.toast(this, R.string.developer_enabled_toast);
        });
        tikwmToggle = findViewById(R.id.developer_tikwm);
        tikwmToggle.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("tikwmFallback", true));
        tikwmToggle.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("tikwmFallback", checked).apply();
            Diagnostics.log("TIKTOK", "TikWM fallback " + (checked ? "enabled" : "disabled"));
        });
        ssstikToggle = findViewById(R.id.developer_ssstik);
        ssstikToggle.setChecked(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("ssstikFallback", true));
        ssstikToggle.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("ssstikFallback", checked).apply();
            Diagnostics.log("TIKTOK", "SSSTik fallback " + (checked ? "enabled" : "disabled"));
        });
        findViewById(R.id.developer_back).setOnClickListener(v -> finish());
        findViewById(R.id.developer_open).setOnClickListener(v -> openDetector());
        findViewById(R.id.developer_refresh).setOnClickListener(v -> renderLog());
        findViewById(R.id.developer_clear).setOnClickListener(v -> { Diagnostics.clear(); renderLog(); });
        findViewById(R.id.developer_copy).setOnClickListener(v -> {
            Diagnostics.copy(this); Ui.toast(this, R.string.developer_copied);
        });
        findViewById(R.id.developer_share).setOnClickListener(v -> {
            if (Diagnostics.count() == 0) { Ui.toast(this, R.string.developer_empty); return; }
            Diagnostics.share(this);
        });
        updateStatus();
        renderLog();
    }

    private void openDetector() {
        if (url == null || url.trim().isEmpty()) { Ui.toast(this, R.string.url_invalid); return; }
        try { url = LinkParser.normalize(url); }
        catch (IllegalArgumentException e) { Ui.toast(this, R.string.url_invalid); return; }
        Diagnostics.log("DEV", "Open detector from developer screen: " + url);
        startActivity(new Intent(this, BrowserActivity.class).putExtra("url", url));
    }

    private void updateStatus() {
        boolean on = Diagnostics.isEnabled(this);
        boolean remote = Diagnostics.remoteDebuggingActive(this);
        statusView.setText(getString(on ? R.string.developer_status_on : R.string.developer_status_off)
                + "\n" + getString(remote ? R.string.developer_remote_on : R.string.developer_remote_off));
    }

    private void renderLog() {
        String text = Diagnostics.text();
        logView.setText(text.isEmpty() ? getString(R.string.developer_empty) : text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override protected void onResume() { super.onResume(); main.postDelayed(refresh, 500); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
