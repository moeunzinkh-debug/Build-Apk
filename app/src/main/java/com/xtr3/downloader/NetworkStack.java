package com.xtr3.downloader;

import android.content.Context;
import org.chromium.net.CronetEngine;
import org.chromium.net.CronetProvider;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Prefers the app-packaged Chromium network stack (HTTP/2 + QUIC) for media transfers.
 * The platform stack remains a fallback; this does not bypass server access decisions or DRM.
 */
public final class NetworkStack {
    private static volatile CronetEngine embedded;
    private static volatile String description = "platform HttpURLConnection";

    private NetworkStack() { }

    public static void init(Context context) {
        if (embedded != null) return;
        try {
            for (CronetProvider provider : CronetProvider.getAllProviders(context.getApplicationContext())) {
                if (!CronetProvider.PROVIDER_NAME_APP_PACKAGED.equals(provider.getName())) continue;
                CronetEngine.Builder builder = provider.createBuilder();
                builder.enableHttp2(true);
                builder.enableQuic(true);
                builder.enableBrotli(true);
                builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0);
                embedded = builder.build();
                description = "embedded Cronet " + embedded.getVersionString();
                Diagnostics.log("NET", "Media network stack: " + description);
                return;
            }
            Diagnostics.log("NET", "Embedded Cronet provider not found; using platform HttpURLConnection");
        } catch (RuntimeException | LinkageError e) {
            embedded = null;
            description = "platform HttpURLConnection (Cronet unavailable)";
            Diagnostics.log("NET", "Cronet initialization failed: " + e.getClass().getSimpleName());
        }
    }

    public static boolean hasEmbedded() { return embedded != null; }
    public static String description() { return description; }

    public static HttpURLConnection open(String url, boolean preferEmbedded) throws IOException {
        URL target = new URL(url);
        CronetEngine engine = embedded;
        if (preferEmbedded && engine != null) {
            try {
                return (HttpURLConnection) engine.openConnection(target);
            } catch (IOException | RuntimeException e) {
                Diagnostics.log("NET", "Cronet connection failed; falling back to platform stack: "
                        + e.getClass().getSimpleName());
            }
        }
        return (HttpURLConnection) target.openConnection();
    }
}
