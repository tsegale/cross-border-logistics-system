package com.freightflow.logistics.dcsa;

/** Refusals of a carrier notification (ADR 0037), each a problem type in docs/problems.md. */
public final class DcsaExceptions {

    private DcsaExceptions() {
    }

    /** No such carrier subscription, or the Notification-Signature does not match the body. */
    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }

    /** An event created outside the replay window: too old, or in the future. */
    public static class ReplayRejectedException extends RuntimeException {
        public ReplayRejectedException(String message) {
            super(message);
        }
    }

    /** A body that is not a DCSA event array. */
    public static class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message) {
            super(message);
        }
    }
}
