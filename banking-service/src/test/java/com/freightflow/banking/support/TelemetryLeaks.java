package com.freightflow.banking.support;

import io.opentelemetry.sdk.trace.data.SpanData;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What must never leave in telemetry (ADR 0024, ADR 0030): an email address or a bearer token, in a span's name,
 * attributes or events, or in a log line.
 */
public final class TelemetryLeaks {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** A bearer token, or an Authorization header written out as a key: Kafka's "authorization failed" is not one. */
    private static final Pattern TOKEN =
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{8,}|authorization\"?\\s*[:=]");

    private TelemetryLeaks() {
    }

    /** Every value of the spans that looks like personal data or a credential, with where it was. */
    public static List<String> in(List<SpanData> spans) {
        List<String> found = new ArrayList<>();
        for (SpanData span : spans) {
            check(found, span.getName() + " name", span.getName());
            span.getAttributes().forEach((key, value) -> {
                check(found, span.getName() + " " + key.getKey(), key.getKey());
                check(found, span.getName() + " " + key.getKey(), String.valueOf(value));
            });
            span.getEvents().forEach(event -> {
                check(found, span.getName() + " event", event.getName());
                event.getAttributes().forEach((key, value) ->
                        check(found, span.getName() + " event " + key.getKey(), String.valueOf(value)));
            });
        }
        return found;
    }

    /** The same for captured log output. */
    public static List<String> in(String logs) {
        List<String> found = new ArrayList<>();
        logs.lines().forEach(line -> check(found, "log", line));
        return found;
    }

    private static void check(List<String> found, String where, String value) {
        if (value != null && (EMAIL.matcher(value).find() || TOKEN.matcher(value).find())) {
            found.add(where + ": " + value);
        }
    }
}
