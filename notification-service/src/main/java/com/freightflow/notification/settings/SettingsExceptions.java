package com.freightflow.notification.settings;

/** The settings' problems (docs/problems.md). */
public final class SettingsExceptions {

    private SettingsExceptions() {
    }

    /** 400 invalid-notification-settings. */
    public static class InvalidSettingsException extends RuntimeException {
        public InvalidSettingsException(String message) {
            super(message);
        }
    }

    /** 412 precondition-failed: If-Match named a version that is no longer current. */
    public static class SettingsChangedException extends RuntimeException {
        private final String expected;
        private final String current;

        public SettingsChangedException(String expected, String current) {
            super("The settings changed since version " + expected + "; the current version is " + current);
            this.expected = expected;
            this.current = current;
        }

        public String expected() {
            return expected;
        }

        public String current() {
            return current;
        }
    }
}
