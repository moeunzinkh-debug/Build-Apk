package com.xtr3.downloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A real, user-visible browser fallback for players that create media URLs in JavaScript. */
public final class BrowserActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, MediaCandidate> candidates = new LinkedHashMap<>();
    private WebView web;
    private TextView status;
    private Button choose;
    private ProgressBar progress;
    private String originalUrl;
    private volatile String currentPage;
    private String detectorScript;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private final Runnable poll = new Runnable() {
        @Override public void run() { scan(); main.postDelayed(this, 2000); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);
        Ui.insets(this, findViewById(R.id.browser_root));
        originalUrl = getIntent().getStringExtra("url");
        if (originalUrl == null) { finish(); return; }
        try { originalUrl = LinkParser.normalize(originalUrl); }
        catch (IllegalArgumentException e) { Ui.toast(this, R.string.url_invalid); finish(); return; }
        currentPage = originalUrl;
        status = findViewById(R.id.browser_status);
        choose = findViewById(R.id.browser_use);
        progress = findViewById(R.id.browser_progress);
        web = findViewById(R.id.webview);
        findViewById(R.id.browser_back).setOnClickListener(v -> back());
        findViewById(R.id.browser_reload).setOnClickListener(v -> web.reload());
        findViewById(R.id.browser_scan).setOnClickListener(v -> scan());
        choose.setOnClickListener(v -> chooseCandidate());
        detectorScript = asset("detector.js");
        configure();
        status.setText(R.string.browser_loading);
        web.loadUrl(originalUrl);
    }

    private void configure() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(HttpMediaClient.USER_AGENT);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new MediaBridge(), "Xtr3Detector");
        web.setDownloadListener((url, userAgent, disposition, mime, size) -> {
            if (url != null && url.startsWith("http")) add(url, mime, disposition, web.getUrl());
            else Ui.toast(this, R.string.no_media_body);
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return true;
                if (request.isForMainFrame() && request.getMethod().equals("GET") && detected(uri.toString(), null)) {
                    add(uri.toString(), null, null, view.getUrl());
                    return true;
                }
                return false;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (detected(url, null)) add(url, null, null, currentPage);
                return null;
            }
            @Override public void onLoadResource(WebView view, String url) {
                if (detected(url, null)) add(url, null, null, view.getUrl());
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentPage = url;
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                scan();
                updateCount();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    status.setText(R.string.browser_page_error);
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                progress.setVisibility(View.GONE);
                status.setText(R.string.browser_ssl_error);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) { progress.setProgress(newProgress); }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                FrameLayout content = findViewById(android.R.id.content);
                content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }
            @Override public void onHideCustomView() { hideCustomView(); }
        });
    }

    private boolean detected(String url, String mime) {
        if (url == null || url.length() > 8192) return false;
        String path = LinkParser.path(url);
        // Individual HLS transport fragments are not downloadable videos.
        if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac")) return false;
        return LinkParser.isVideo(url, mime);
    }
    private void add(String url, String mime, String title, String page) {
        if (!detected(url, mime)) return;
        String normalized;
        try { normalized = LinkParser.normalize(url); } catch (IllegalArgumentException e) { return; }
        synchronized (candidates) {
            if (candidates.containsKey(normalized) || candidates.size() >= 24) return;
            MediaCandidate c = new MediaCandidate(normalized, page == null ? originalUrl : page,
                    title == null || title.trim().isEmpty() ? "Video" : LinkParser.cleanTitle(title),
                    LinkParser.isHls(normalized, mime) ? MediaCandidate.HLS : MediaCandidate.DIRECT);
            c.mime = mime;
            candidates.put(normalized, c);
        }
        main.post(this::updateCount);
    }
    private void updateCount() {
        int count;
        synchronized (candidates) { count = candidates.size(); }
        choose.setEnabled(count > 0);
        status.setText(count == 0 ? getString(R.string.browser_none) : getString(R.string.browser_found, count));
    }
    private void scan() {
        if (web != null && detectorScript != null) web.evaluateJavascript(detectorScript, null);
    }
    private void chooseCandidate() {
        List<MediaCandidate> list;
        synchronized (candidates) { list = new ArrayList<>(candidates.values()); }
        if (list.isEmpty()) { Ui.toast(this, R.string.browser_none); return; }
        if (list.size() == 1) { returnCandidate(list.get(0)); return; }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            MediaCandidate c = list.get(i);
            String path = LinkParser.path(c.url);
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.length() > 45) name = name.substring(0, 45) + "…";
            labels[i] = (MediaCandidate.HLS.equals(c.kind) ? "HLS · " : "VIDEO · ") + (name.isEmpty() ? LinkParser.host(c.url) : name);
        }
        new AlertDialog.Builder(this).setTitle(R.string.choose_video)
                .setItems(labels, (d, which) -> returnCandidate(list.get(which)))
                .setNegativeButton(R.string.cancel, null).show();
    }
    private void returnCandidate(MediaCandidate c) {
        CookieManager.getInstance().flush();
        if (c.title == null || c.title.equals("Video")) c.title = LinkParser.cleanTitle(web.getTitle());
        setResult(RESULT_OK, new android.content.Intent().putExtra("candidate", c));
        finish();
    }

    public final class MediaBridge {
        @JavascriptInterface public void report(String json) {
            if (json == null || json.length() > 200_000) return;
            try {
                JSONArray array = new JSONArray(json);
                String page = originalUrl;
                for (int i = 0; i < Math.min(array.length(), 24); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "");
                    String mime = "hls".equals(o.optString("kind")) ? "application/vnd.apple.mpegurl" : null;
                    add(url, mime, o.optString("title", "Video"), page);
                }
            } catch (org.json.JSONException | RuntimeException ignored) { }
        }
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) { return ""; }
    }
    private void hideCustomView() {
        if (customView == null) return;
        FrameLayout content = findViewById(android.R.id.content);
        content.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }
    private void back() {
        if (customView != null) hideCustomView();
        else if (web.canGoBack()) web.goBack();
        else finish();
    }
    @Override public void onBackPressed() { back(); }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); main.postDelayed(poll, 1500); }
    }
    @Override protected void onPause() {
        main.removeCallbacks(poll);
        if (web != null) { web.onPause(); web.pauseTimers(); }
        CookieManager.getInstance().flush();
        super.onPause();
    }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        hideCustomView();
        if (web != null) {
            web.removeJavascriptInterface("Xtr3Detector");
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
