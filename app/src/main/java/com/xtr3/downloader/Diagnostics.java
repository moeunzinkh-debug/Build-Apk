package com.xtr3.downloader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.webkit.WebView;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Opt-in developer log. It never logs Cookie/Authorization header values. */
public final class Diagnostics {
    private static final int MAX_ENTRIES = 900;
    private static final int MAX_ENTRY_LENGTH = 1400;
    private static final ArrayDeque<String> ENTRIES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static volatile boolean enabled;
    private static volatile boolean loaded;

    private Diagnostics() { }

    public static synchronized void init(Context context) {
        enabled = prefs(context).getBoolean("developerMode", false);
        loaded = true;
        applyWebViewDebugging(context);
    }

    public static boolean isEnabled(Context context) {
        if (!loaded) init(context);
        return enabled;
    }

    public static synchronized void setEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean("developerMode", value).apply();
        enabled = value;
        loaded = true;
        applyWebViewDebugging(context);
        log("DEV", value ? "Developer logging enabled" : "Developer logging disabled");
    }

    public static void applyWebViewDebugging(Context context) {
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable && isEnabled(context));
    }

    public static boolean remoteDebuggingActive(Context context) {
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return debuggable && isEnabled(context);
    }

    public static void log(String tag, String message) {
        if (!enabled || message == null) return;
        String clean = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_ENTRY_LENGTH) clean = clean.substring(0, MAX_ENTRY_LENGTH) + "…";
        String line;
        synchronized (TIME) { line = TIME.format(new Date()); }
        line += " [" + tag + "] " + clean;
        synchronized (ENTRIES) {
            ENTRIES.addLast(line);
            while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
        }
    }

    public static String text() {
        synchronized (ENTRIES) {
            if (ENTRIES.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            for (String line : ENTRIES) out.append(line).append('\n');
            return out.toString();
        }
    }

    public static int count() { synchronized (ENTRIES) { return ENTRIES.size(); } }
    public static void clear() { synchronized (ENTRIES) { ENTRIES.clear(); } }

    public static void copy(Context context) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Xtr3 developer log", text()));
    }

    public static void share(Context context) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Xtr3 developer log");
        send.putExtra(Intent.EXTRA_TEXT, text());
        context.startActivity(Intent.createChooser(send, "Share Xtr3 log"));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("developer", Context.MODE_PRIVATE);
    }
}
