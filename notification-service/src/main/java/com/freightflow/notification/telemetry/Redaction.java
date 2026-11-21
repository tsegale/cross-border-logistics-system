package com.freightflow.notification.telemetry;

import java.util.regex.Pattern;

/**
 * Takes email addresses out of text bound for logs and spans (ADR 0024, ADR 0030): an SMTP server's refusal quotes
 * the recipient ({@code 550 5.1.1 <someone@example.org> unknown}). The collector's redaction processor is the second
 * line behind this (ADR 0031).
 */
public final class Redaction {

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private Redaction() {
    }

    public static String emails(String text) {
        return text == null ? null : EMAIL.matcher(text).replaceAll("<email>");
    }
}
