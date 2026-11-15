package com.xtr3.downloader;

import java.util.regex.Pattern;

/** Redacts common credential/token fields before developer logs are shown or shared. */
public final class LogSanitizer {
    private static final String KEYS =
            "rpn|rpk|rpc|rpf|rps|token|access_token|refresh_token|auth|authorization|api_key|apikey|key|secret|signature|sig|session|sessionid|sid|password|pwd";
    private static final Pattern QUERY = Pattern.compile(
            "(?i)([?&](?:" + KEYS + ")=)[^&#\\s\"'<>\\\\]+");
    private static final Pattern JSON_FIELD = Pattern.compile(
            "(?i)(\"(?:" + KEYS + ")\"\\s*:\\s*\")[^\"]*(\")");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/\\-]+=*");

    private LogSanitizer() { }

    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input;
        String out = QUERY.matcher(input).replaceAll("$1[redacted]");
        out = JSON_FIELD.matcher(out).replaceAll("$1[redacted]$2");
        out = BEARER.matcher(out).replaceAll("$1[redacted]");
        return out;
    }
}
