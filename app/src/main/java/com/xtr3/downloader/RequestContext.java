package com.xtr3.downloader;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Headers replayed from the player's own media request so a session-bound CDN
 * sees the same context it saw during playback. Values are never logged or persisted;
 * cookies still come from the shared cookie jar at request time.
 */
public final class RequestContext {
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "user-agent", "referer", "origin", "accept", "accept-language",
            "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform"));

    private RequestContext() { }

    /**
     * Keeps only replay-safe request headers. Cookies, authorization tokens, range,
     * encoding and hop-by-hop headers are never captured or forwarded.
     */
    public static Map<String, String> capture(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!ALLOWED.contains(key)) continue;
            String value = entry.getValue().trim();
            if (value.isEmpty() || value.length() > 2048 || containsControl(value)) continue;
            out.put(key, value);
        }
        return out;
    }

    /** Captured player headers win over the generic page referer; the allowlist is re-applied. */
    public static Map<String, String> replay(Map<String, String> captured, String referer) {
        Map<String, String> out = capture(captured);
        if (referer != null && !referer.isEmpty() && referer.length() <= 2048 && !containsControl(referer)) {
            out.putIfAbsent("referer", referer.trim());
        }
        return out;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }
}
