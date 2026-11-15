package com.xtr3.downloader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure, unit-tested argument construction for the bundled yt-dlp engine. */
public final class PlatformOptions {
    public static final String MAX_FILE_SIZE = "1500M";
    public static final String FORMAT = "bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b";
    public static final String SORT = "res:1080,vcodec:h264,acodec:aac";

    private PlatformOptions() { }

    public static List<String> info(String url) {
        List<String> args = common(url);
        args.add("--dump-single-json");
        args.add("--skip-download");
        return args;
    }

    public static List<String> download(String url, String outputDirectory) {
        List<String> args = common(url);
        args.add("--newline");
        args.add("--progress");
        args.add("--no-simulate");
        args.add("--no-continue");
        args.add("--part");
        args.add("--paths"); args.add(outputDirectory);
        args.add("--output"); args.add("video.%(ext)s");
        args.add("--print"); args.add("after_move:filepath");
        args.add("--windows-filenames");
        args.add("--trim-filenames"); args.add("120");
        args.add("--merge-output-format"); args.add("mp4/mkv");
        args.add("--concurrent-fragments"); args.add("4");
        args.add("--file-access-retries"); args.add("3");
        return args;
    }

    private static List<String> common(String url) {
        String normalized = SupportedSite.canonical(LinkParser.normalize(url));
        if (SupportedSite.identify(normalized) == null || SupportedSite.isCollection(normalized)) {
            throw new IllegalArgumentException("Unsupported platform link.");
        }
        List<String> args = new ArrayList<>();
        args.add("--ignore-config");
        args.add("--no-config-locations");
        args.add("--no-plugin-dirs");
        args.add("--no-remote-components");
        args.add("--no-cookies");
        args.add("--no-cookies-from-browser");
        args.add("--netrc-location"); args.add("/dev/null");
        args.add("--no-mark-watched");
        args.add("--no-playlist");
        args.add("--playlist-items"); args.add("1");
        args.add("--no-live-from-start");
        args.add("--no-wait-for-video");
        args.add("--match-filters"); args.add("!is_live & !has_drm");
        args.add("--max-filesize"); args.add(MAX_FILE_SIZE);
        args.add("--format"); args.add(FORMAT);
        args.add("--format-sort"); args.add(SORT);
        args.add("--socket-timeout"); args.add("20");
        args.add("--retries"); args.add("3");
        args.add("--fragment-retries"); args.add("3");
        args.add("--extractor-retries"); args.add("2");
        args.add(normalized);
        return args;
    }

    /** Maps yt-dlp's English diagnostics to a stable localizable error category. */
    public static String errorCategory(String diagnostic) {
        String text = diagnostic == null ? "" : diagnostic.toLowerCase(Locale.ROOT);
        if (text.contains("drm") || text.contains("widevine") || text.contains("unplayable")) return "protected";
        if (text.contains("premium") || text.contains("paid ") || text.contains("purchase")
                || text.contains("members only") || text.contains("members-only") || text.contains("subscription")) return "paid";
        if (text.contains("login") || text.contains("log in") || text.contains("sign in")
                || text.contains("cookies") || text.contains("account") || text.contains("authentication")) return "login";
        if (text.contains("private video") || text.contains("video is private") || text.contains("not available")
                || text.contains("unavailable") || text.contains("removed") || text.contains("deleted")) return "unavailable";
        if (text.contains("live") || text.contains("premieres") || text.contains("upcoming")) return "live";
        if (text.contains("unsupported url") || text.contains("no video formats") || text.contains("no formats")) return "unsupported";
        if (text.contains("429") || text.contains("too many requests") || text.contains("rate limit")) return "rate";
        if (text.contains("403") || text.contains("forbidden")) return "forbidden";
        if (text.contains("ssl") || text.contains("tls") || text.contains("timed out") || text.contains("timeout")
                || text.contains("network") || text.contains("resolve") || text.contains("connection")) return "network";
        return "generic";
    }
}
