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
import android.webkit.ConsoleMessage;
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
    private Button choose, logButton;
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
        Diagnostics.init(this);
        originalUrl = getIntent().getStringExtra("url");
        if (originalUrl == null) { finish(); return; }
        try { originalUrl = LinkParser.normalize(originalUrl); }
        catch (IllegalArgumentException e) { Ui.toast(this, R.string.url_invalid); finish(); return; }
        currentPage = originalUrl;
        Diagnostics.log("OPEN", originalUrl);
        status = findViewById(R.id.browser_status);
        choose = findViewById(R.id.browser_use);
        logButton = findViewById(R.id.browser_log);
        progress = findViewById(R.id.browser_progress);
        web = findViewById(R.id.webview);
        findViewById(R.id.browser_back).setOnClickListener(v -> back());
        findViewById(R.id.browser_reload).setOnClickListener(v -> { Diagnostics.log("DEV", "Reload"); web.reload(); });
        findViewById(R.id.browser_scan).setOnClickListener(v -> { Diagnostics.log("DEV", "Manual scan"); scan(); });
        logButton.setVisibility(Diagnostics.isEnabled(this) ? View.VISIBLE : View.GONE);
        logButton.setOnClickListener(v -> startActivity(new android.content.Intent(this, DeveloperActivity.class)
                .putExtra("url", currentPage == null ? originalUrl : currentPage)));
        choose.setOnClickListener(v -> chooseCandidate());
        detectorScript = asset("detector.js");
        configure();
        status.setText(R.string.browser_loading);
        web.loadUrl(originalUrl);
    }

    private void configure() {
        Diagnostics.applyWebViewDebugging(this);
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
            Diagnostics.log("DL", "mime=" + mime + " size=" + size + " url=" + url);
            if (isAppDownload(url, mime)) {
                Diagnostics.log("BLOCKED_APK", "The page tried to download an application, not a video: " + url);
                Ui.toast(this, R.string.browser_blocked_app);
                return;
            }
            if (url != null && url.startsWith("http")) add(url, mime, disposition, currentPage, false);
            else Ui.toast(this, R.string.no_media_body);
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                    Diagnostics.log("NAV", "Blocked non-web scheme: " + uri);
                    return true;
                }
                if (request.isForMainFrame() && isAppDownload(uri.toString(), null)) {
                    Diagnostics.log("BLOCKED_APK", "Blocked main-frame app download: " + uri);
                    Ui.toast(this, R.string.browser_blocked_app);
                    return true;
                }
                if (request.isForMainFrame() && request.getMethod().equals("GET") && detected(uri.toString(), null)) {
                    Diagnostics.log("HIT", "Main-frame navigation media: " + uri);
                    add(uri.toString(), null, null, currentPage, false);
                    return true;
                }
                return false;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String range = null;
                try {
                    Map<String, String> headers = request.getRequestHeaders();
                    if (headers != null) range = headers.get("Range");
                    if (range == null && headers != null) range = headers.get("range");
                } catch (RuntimeException ignored) { }
                String method = request.getMethod() == null ? "GET" : request.getMethod();
                Diagnostics.log("REQ", method + (request.isForMainFrame() ? " main " : " ")
                        + url + (range == null ? "" : " Range=" + range));
                // POST/OPTIONS API calls (video/detail, hot_search, rtcOffer, report/play) are not video files.
                if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return null;
                if (detected(url, null)) {
                    Diagnostics.log("HIT", url);
                    add(url, null, null, currentPage, false);
                }
                return null;
            }
            @Override public void onLoadResource(WebView view, String url) {
                if (detected(url, null)) add(url, null, null, currentPage, false);
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (currentPage != null && !currentPage.equals(url)) {
                    synchronized (candidates) { candidates.clear(); }
                    main.post(BrowserActivity.this::updateCount);
                }
                currentPage = url;
                Diagnostics.log("PAGE", "Started " + url);
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                currentPage = url;
                Diagnostics.log("PAGE", "Finished " + url + " title=" + view.getTitle());
                progress.setVisibility(View.GONE);
                scan();
                updateCount();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                Diagnostics.log("ERR", "code=" + error.getErrorCode() + " " + error.getDescription() + " url=" + request.getUrl());
                if (request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    status.setText(R.string.browser_page_error);
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                Diagnostics.log("HTTP", "status=" + errorResponse.getStatusCode() + " url=" + request.getUrl());
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                Diagnostics.log("SSL", error.toString());
                handler.cancel();
                progress.setVisibility(View.GONE);
                status.setText(R.string.browser_ssl_error);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) { progress.setProgress(newProgress); }
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                Diagnostics.log("CONSOLE", message.messageLevel() + " " + message.message()
                        + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
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

    private boolean isAppDownload(String url, String mime) {
        if (url != null && LinkParser.path(url).endsWith(".apk")) return true;
        String m = mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT);
        return m.contains("android.package-archive") || m.equals("application/vnd.android.package-archive");
    }
    private boolean detected(String url, String mime) {
        if (url == null || url.length() > 8192) return false;
        if (LinkParser.isFragment(url)) return false;
        String path = LinkParser.path(url);
        if (path.endsWith(".mpd")) {
            Diagnostics.log("DASH", "MPEG-DASH manifest detected but this build downloads HLS/direct only: " + url);
            return false;
        }
        return LinkParser.isProbablyMedia(url, mime);
    }
    private void add(String url, String mime, String title, String page, boolean trustedVideo) {
        if (!trustedVideo && !detected(url, mime)) return;
        String normalized;
        try { normalized = LinkParser.normalize(url); } catch (IllegalArgumentException e) { return; }
        if (LinkParser.isFragment(normalized) || LinkParser.path(normalized).endsWith(".mpd")) return;
        synchronized (candidates) {
            if (candidates.containsKey(normalized) || candidates.size() >= 24) return;
            MediaCandidate c = new MediaCandidate(normalized, page == null ? originalUrl : page,
                    title == null || title.trim().isEmpty() ? "Video" : LinkParser.cleanTitle(title),
                    LinkParser.isHls(normalized, mime) ? MediaCandidate.HLS : MediaCandidate.DIRECT);
            c.mime = mime;
            candidates.put(normalized, c);
            Diagnostics.log("MEDIA", (trustedVideo ? "video-element " : "network ") + c.kind + " " + normalized);
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
        Diagnostics.log("DEV", "Choose video; candidates=" + list.size());
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
        Diagnostics.log("DEV", "Selected " + c.kind + " " + c.url);
        setResult(RESULT_OK, new android.content.Intent().putExtra("candidate", c));
        finish();
    }

    public final class MediaBridge {
        @JavascriptInterface public void report(String json) {
            if (json == null || json.length() > 200_000) return;
            try {
                JSONArray array = new JSONArray(json);
                Diagnostics.log("JS", "Detector reported " + array.length() + " candidate(s)");
                String page = currentPage == null ? originalUrl : currentPage;
                for (int i = 0; i < Math.min(array.length(), 24); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "");
                    String kind = o.optString("kind", "");
                    String mime = "hls".equals(kind) || LinkParser.isHls(url, null) ? "application/vnd.apple.mpegurl" : null;
                    add(url, mime, o.optString("title", "Video"), page, "video".equals(kind));
                }
            } catch (org.json.JSONException | RuntimeException e) {
                Diagnostics.log("JS", "Invalid detector report: " + e);
            }
        }
        @JavascriptInterface public void trace(String json) {
            if (json == null || json.length() > 200_000) return;
            Diagnostics.log("TRACE", json);
        }
        @JavascriptInterface public void event(String json) {
            if (json == null || json.length() > 8_000) return;
            Diagnostics.log("PLAYER", json);
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
        Diagnostics.applyWebViewDebugging(this);
        if (logButton != null) logButton.setVisibility(Diagnostics.isEnabled(this) ? View.VISIBLE : View.GONE);
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
