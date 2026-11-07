package com.freightflow.logistics.shipment;

/**
 * Formats and validates HTTP {@code ETag} / {@code If-Match} headers backed by {@code shipments.version}.
 */
public final class ETags {

    private ETags() {
    }

    public static String format(long version) {
        return "\"" + version + "\"";
    }

    public static void assertMatches(String ifMatchHeader, long currentVersion) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            return;
        }
        String trimmed = ifMatchHeader.trim();
        if ("*".equals(trimmed)) {
            return;
        }
        String currentTag = format(currentVersion);
        String stripped = trimmed;
        if (stripped.startsWith("W/")) {
            stripped = stripped.substring(2).trim();
        }
        if (stripped.startsWith("\"") && stripped.endsWith("\"") && stripped.length() >= 2) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        long expectedVersion;
        try {
            expectedVersion = Long.parseLong(stripped);
        } catch (NumberFormatException ex) {
            throw new PreconditionFailedException(
                    ifMatchHeader,
                    currentTag,
                    "If-Match header " + ifMatchHeader + " does not match current version " + currentTag
            );
        }
        if (expectedVersion != currentVersion) {
            throw new PreconditionFailedException(
                    ifMatchHeader,
                    currentTag,
                    "If-Match version \"" + expectedVersion + "\" is stale; current ETag is " + currentTag
            );
        }
    }
}
