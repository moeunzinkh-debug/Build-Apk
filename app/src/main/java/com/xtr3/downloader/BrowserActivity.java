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
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.ScriptHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A real, user-visible browser fallback for players that create media URLs in JavaScript. */
public final class BrowserActivity extends Activity {
    private static final String DESKTOP_UA = HttpMediaClient.DESKTOP_USER_AGENT;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, MediaCandidate> candidates = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> seenHeaders = new LinkedHashMap<>();
    private volatile String requestUserAgent = HttpMediaClient.USER_AGENT;
    private WebView web;
    private TextView status;
    private Button choose, logButton, desktopButton;
    private ProgressBar progress;
    private String originalUrl;
    private volatile String currentPage;
    private volatile int gateMessage;
    private boolean desktopMode;
    private String detectorScript, observerScript;
    private ScriptHandler observerHandle;
    private volatile boolean bufferedPlayback, peerPlayback, protectedPlayback;
    private volatile boolean destroyed;
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
        findViewById(R.id.browser_scan).setOnClickListener(v -> { Diagnostics.log("DEV", "Grab source"); grabSource(); });
        desktopMode = getSharedPreferences("browser", MODE_PRIVATE).getBoolean("desktopMode", false);
        desktopButton = findViewById(R.id.browser_desktop);
        desktopButton.setOnClickListener(v -> toggleDesktop());
        applyDesktopUi();
        logButton.setVisibility(Diagnostics.isEnabled(this) ? View.VISIBLE : View.GONE);
        logButton.setOnClickListener(v -> startActivity(new android.content.Intent(this, DeveloperActivity.class)
                .putExtra("url", currentPage == null ? originalUrl : currentPage)));
        choose.setOnClickListener(v -> chooseCandidate());
        detectorScript = asset("detector.js");
        observerScript = asset("observer.js");
        configure();
        Diagnostics.log("APP", getString(R.string.version) + "; desktop=" + desktopMode
                + "; frameObserver=" + (observerHandle != null));
        status.setText(R.string.browser_loading);
        web.loadUrl(originalUrl);
    }

    private void configure() {
        Diagnostics.applyWebViewDebugging(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(desktopMode ? DESKTOP_UA : HttpMediaClient.USER_AGENT);
        requestUserAgent = settings.getUserAgentString();
        settings.setUseWideViewPort(desktopMode);
        settings.setLoadWithOverviewMode(desktopMode);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
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
        installObserver();
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
                    Ui.toast(BrowserActivity.this, R.string.browser_blocked_app);
                    return true;
                }
                if (request.isForMainFrame() && request.getMethod().equals("GET") && detected(uri.toString(), null)) {
                    Diagnostics.log("HIT", "Main-frame navigation media: " + uri);
                    rememberHeaders(uri.toString(), request.getRequestHeaders());
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
                Diagnostics.logChanged("REQ:" + method + ":" + url, "REQ", method
                        + (request.isForMainFrame() ? " main " : " ") + url + (range == null ? "" : " Range=" + range));
                // POST/OPTIONS API calls (video/detail, hot_search, rtcOffer, report/play) are not video files.
                if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return null;
                // Remember the player's request context even when the extension-less URL only
                // becomes a candidate later (the observer reports it once Content-Type is known).
                if (!LinkParser.isFragment(url) && !LinkParser.isNonMediaUrl(url)) {
                    rememberHeaders(url, request.getRequestHeaders());
                }
                if (detected(url, null)) {
                    Diagnostics.log("HIT", url);
                    add(url, null, null, currentPage, false);
                }
                return null;
            }
            @Override public void onLoadResource(WebView view, String url) {
                if (detected(url, null)) add(url, null, null, currentPage, false);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (!isReload && currentPage != null && !currentPage.equals(url)) resetDetection();
                currentPage = url;
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentPage = url;
                resetDetection();
                Diagnostics.log("PAGE", "Started " + url);
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (currentPage != null && !currentPage.equals(url)) resetDetection();
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

    private void installObserver() {
        if (observerScript == null || observerScript.isEmpty()) return;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                // Player frames may be cross-origin. This passive observer runs only while the
                // user-initiated detector is open; every bridge payload is validated in Java.
                // It never receives request bodies, authorization headers, cookies, or DRM keys.
                observerHandle = WebViewCompat.addDocumentStartJavaScript(web, observerScript,
                        Collections.singleton("*"));
            } catch (RuntimeException e) {
                Diagnostics.log("DEV", "Frame observer unavailable: " + e.getClass().getSimpleName());
            }
        }
        if (observerHandle == null) Diagnostics.log("DEV", "Document-start frame observer unsupported; main-frame scan only");
    }

    private void applyDesktopUi() {
        if (desktopButton == null) return;
        desktopButton.setText(desktopMode ? R.string.browser_desktop_on : R.string.browser_desktop);
        desktopButton.setAlpha(desktopMode ? 1f : 0.75f);
    }
    private void toggleDesktop() {
        desktopMode = !desktopMode;
        getSharedPreferences("browser", MODE_PRIVATE).edit().putBoolean("desktopMode", desktopMode).apply();
        Diagnostics.log("DEV", "Desktop site mode " + (desktopMode ? "ON" : "OFF"));
        applyDesktopUi();
        WebSettings settings = web.getSettings();
        settings.setUserAgentString(desktopMode ? DESKTOP_UA : HttpMediaClient.USER_AGENT);
        requestUserAgent = settings.getUserAgentString();
        settings.setUseWideViewPort(desktopMode);
        settings.setLoadWithOverviewMode(desktopMode);
        resetDetection();
        String url = currentPage == null ? originalUrl : currentPage;
        web.loadUrl(url);
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
        if (LinkParser.isFragment(normalized) || LinkParser.path(normalized).endsWith(".mpd")
                || LinkParser.isNonMediaUrl(normalized)
                || (mime != null && LinkParser.isKnownNonMediaMime(mime))) return;
        synchronized (candidates) {
            if (candidates.containsKey(normalized) || candidates.size() >= 24) return;
            MediaCandidate c = new MediaCandidate(normalized, page == null ? originalUrl : page,
                    title == null || title.trim().isEmpty() ? "Video" : LinkParser.cleanTitle(title),
                    LinkParser.isHls(normalized, mime) ? MediaCandidate.HLS : MediaCandidate.DIRECT);
            c.mime = mime;
            Map<String, String> context;
            synchronized (seenHeaders) { context = seenHeaders.get(normalized); }
            if (context != null && !context.isEmpty()) c.headers = new LinkedHashMap<>(context);
            candidates.put(normalized, c);
            Diagnostics.log("MEDIA", (trustedVideo ? "video-element " : "network ") + c.kind + " " + normalized);
        }
        main.post(this::updateCount);
    }
    /** Keeps the player's own replay-safe request headers so session-bound CDNs accept the download. */
    private void rememberHeaders(String url, Map<String, String> raw) {
        String key;
        try { key = LinkParser.normalize(url); } catch (IllegalArgumentException e) { return; }
        Map<String, String> captured = RequestContext.capture(raw);
        // Some WebView versions omit User-Agent/Referer from getRequestHeaders(); fall back
        // to the effective browser UA and the page that initiated the request.
        captured.putIfAbsent("user-agent", requestUserAgent);
        captured = RequestContext.capture(RequestContext.replay(captured, currentPage));
        if (captured.isEmpty()) return;
        synchronized (seenHeaders) {
            seenHeaders.remove(key);
            if (seenHeaders.size() >= 64) {
                java.util.Iterator<String> oldest = seenHeaders.keySet().iterator();
                if (oldest.hasNext()) { oldest.next(); oldest.remove(); }
            }
            seenHeaders.put(key, captured);
        }
    }
    private void resetDetection() {
        synchronized (candidates) { candidates.clear(); }
        synchronized (seenHeaders) { seenHeaders.clear(); }
        gateMessage = 0;
        bufferedPlayback = false;
        peerPlayback = false;
        protectedPlayback = false;
        main.post(this::updateCount);
    }
    private void updateCount() {
        if (destroyed || choose == null || status == null) return;
        int count;
        synchronized (candidates) { count = candidates.size(); }
        choose.setEnabled(count > 0);
        choose.setAlpha(count > 0 ? 1f : 0.45f);
        choose.setText(count > 0 ? R.string.browser_use : R.string.browser_no_link);
        if (count > 0) status.setText(getString(R.string.browser_found, count));
        else if (protectedPlayback) status.setText(R.string.browser_protected);
        else if (peerPlayback) status.setText(R.string.browser_peer);
        else if (bufferedPlayback) status.setText(R.string.browser_buffered);
        else if (gateMessage != 0) status.setText(gateMessage);
        else status.setText(R.string.browser_none);
    }
    private void scan() {
        if (web != null && detectorScript != null) {
            // Also installs a main-frame fallback on older WebView versions.
            web.evaluateJavascript(observerScript + "\n" + detectorScript, null);
        }
    }
    private void grabSource() {
        if (web == null) return;
        // Deep source grab in the main frame: player globals, inline/JSON code, data attributes,
        // iframe srcdoc and resource timing. Embedded frames also grab on load/playing via observer.
        web.evaluateJavascript("window.__xtr3Observer && window.__xtr3Observer.grab ? window.__xtr3Observer.grab(true) : null", null);
        scan();
        main.postDelayed(this::updateCount, 1200);
    }
    private void chooseCandidate() {
        List<MediaCandidate> list;
        synchronized (candidates) { list = new ArrayList<>(candidates.values()); }
        Diagnostics.log("DEV", "Choose video; candidates=" + list.size());
        if (list.isEmpty()) {
            Ui.toast(this, protectedPlayback ? R.string.browser_protected : peerPlayback ? R.string.browser_peer
                    : bufferedPlayback ? R.string.browser_buffered : R.string.browser_none);
            return;
        }
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
        Diagnostics.log("DEV", "Selected " + c.kind + " " + c.url
                + "; context=" + (c.headers == null ? 0 : c.headers.size()));
        setResult(RESULT_OK, new android.content.Intent().putExtra("candidate", c));
        finish();
    }

    public final class MediaBridge {
        @JavascriptInterface public void report(String json) {
            if (json == null || json.length() > 200_000) return;
            try {
                JSONArray array = new JSONArray(json);
                String page = currentPage == null ? originalUrl : currentPage;
                Diagnostics.logChanged("JS:" + page, "JS", "Detector reported " + array.length() + " candidate(s)");
                for (int i = 0; i < Math.min(array.length(), 24); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "");
                    String kind = o.optString("kind", "");
                    String observedMime = o.optString("mime", "");
                    if (observedMime.length() > 200) observedMime = "";
                    String mime = LinkParser.isHls(url, observedMime)
                            || ("hls".equals(kind) && observedMime.isEmpty()) ? "application/vnd.apple.mpegurl"
                            : observedMime.isEmpty() ? null : observedMime;
                    boolean trustedElement = "video".equals(kind) && observedMime.isEmpty();
                    add(url, mime, o.optString("title", "Video"), page, trustedElement);
                }
            } catch (org.json.JSONException | RuntimeException e) {
                Diagnostics.log("JS", "Invalid detector report: " + e);
            }
        }
        @JavascriptInterface public void network(String json) {
            if (json == null || json.length() > 12_000) return;
            try {
                JSONObject o = new JSONObject(json);
                String url = LinkParser.normalize(o.optString("url", ""));
                String mime = o.optString("mime", "");
                if (mime.length() > 200) return;
                if (mime.isEmpty()) mime = null;
                if (!detected(url, mime)) return;
                String source = o.optString("source", "player");
                if (source.length() > 24 || !source.matches("[a-z-]+")) source = "player";
                Diagnostics.log("PLAYER_NET", source + " " + url);
                if (source.endsWith("-code") || "player-config".equals(source) || "dom-code".equals(source)) {
                    Diagnostics.log("GRAB", "Source grabbed from " + source + ": " + url);
                }
                add(url, mime, null, currentPage, false);
            } catch (org.json.JSONException | RuntimeException e) {
                Diagnostics.log("JS", "Rejected invalid player network report");
            }
        }
        @JavascriptInterface public void playback(String json) {
            if (json == null || json.length() > 20_000) return;
            try {
                JSONObject o = new JSONObject(json);
                bufferedPlayback |= o.optBoolean("buffered");
                peerPlayback |= o.optBoolean("peer");
                protectedPlayback |= o.optBoolean("protected");
                String signature = o.optString("href") + o.optBoolean("playing") + o.optBoolean("buffered")
                        + o.optBoolean("peer") + o.optBoolean("protected") + o.optJSONArray("sourceBuffers")
                        + o.optJSONArray("blobs");
                Diagnostics.logChanged("PLAYBACK:" + o.optString("href"), "PLAYBACK", json, signature);
                main.post(BrowserActivity.this::updateCount);
            } catch (org.json.JSONException ignored) { }
        }
        @JavascriptInterface public void trace(String json) {
            if (json == null || json.length() > 200_000) return;
            try {
                JSONObject root = new JSONObject(json);
                Diagnostics.logChanged("TRACE", "TRACE", json, traceSignature(root));
                JSONObject gate = root.optJSONObject("gate");
                if (gate == null) return;
                int res = 0;
                if (gate.optBoolean("appOnly")) res = R.string.browser_gate_app;
                else if (gate.optBoolean("drm")) res = R.string.browser_gate_drm;
                else if (gate.optBoolean("login")) res = R.string.browser_gate_login;
                if (res != gateMessage) {
                    gateMessage = res;
                    if (res != 0) Diagnostics.log("GATE", gate.optString("message", ""));
                    main.post(BrowserActivity.this::updateCount);
                }
            } catch (org.json.JSONException ignored) { }
        }
        @JavascriptInterface public void event(String json) {
            if (json == null || json.length() > 8_000) return;
            Diagnostics.log("PLAYER", json);
        }
    }

    private static String traceSignature(JSONObject root) throws org.json.JSONException {
        JSONObject summary = new JSONObject(root.toString());
        summary.remove("bodyText");
        summary.remove("resources");
        summary.remove("scriptUrls");
        summary.remove("playback");
        JSONArray videos = summary.optJSONArray("videos");
        if (videos != null) for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video != null) video.remove("currentTime");
        }
        return summary.toString();
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
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        hideCustomView();
        if (observerHandle != null) { observerHandle.remove(); observerHandle = null; }
        if (web != null) {
            web.removeJavascriptInterface("Xtr3Detector");
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
