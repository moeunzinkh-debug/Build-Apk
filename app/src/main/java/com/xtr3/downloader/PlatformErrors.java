package com.xtr3.downloader;

import android.content.Context;

/** Localized, credential-free messages for the platform engine's stable error categories. */
public final class PlatformErrors {
    private PlatformErrors() { }

    public static String message(Context context, String code) {
        if (code == null) return null;
        int res;
        switch (code) {
            case "COLLECTION": res = R.string.platform_collection; break;
            case "UNSUPPORTED": res = R.string.platform_unsupported; break;
            case "protected": res = R.string.platform_protected; break;
            case "paid": res = R.string.platform_paid; break;
            case "login": res = R.string.platform_login; break;
            case "unavailable": res = R.string.platform_unavailable; break;
            case "live": res = R.string.platform_live; break;
            case "unsupported": res = R.string.platform_unsupported; break;
            case "rate": res = R.string.platform_rate; break;
            case "forbidden": res = R.string.platform_forbidden; break;
            case "network": res = R.string.error_network; break;
            case "engine": res = R.string.platform_engine; break;
            case "slides": res = R.string.platform_slides; break;
            case "audio": res = R.string.platform_audio; break;
            case "watermark": res = R.string.platform_watermark; break;
            case "challenge": res = R.string.platform_challenge; break;
            case "generic": res = R.string.error_generic; break;
            default: return null;
        }
        return context.getString(res);
    }
}
