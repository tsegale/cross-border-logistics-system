package com.freightflow.logistics.deadletter;

import java.util.UUID;

/** The dead-letter API's problems (docs/problems.md), the same types as notification-service's. */
public final class DeadLetterExceptions {

    private DeadLetterExceptions() {
    }

    /** 404 dead-letter-not-found. */
    public static class DeadLetterNotFoundException extends RuntimeException {
        public DeadLetterNotFoundException(UUID id) {
            super("No dead letter " + id);
        }
    }

    /** 409 dead-letter-already-redriven. */
    public static class DeadLetterAlreadyRedrivenException extends RuntimeException {
        public DeadLetterAlreadyRedrivenException(UUID id) {
            super("Dead letter " + id + " was redriven already");
        }
    }

    /** 409 dead-letter-redrive-failed: handling it failed again; it stays NEW with the error recorded. */
    public static class RedriveFailedException extends RuntimeException {
        private final int attempts;

        public RedriveFailedException(UUID id, int attempts, String error) {
            super("Redriving dead letter " + id + " failed again: " + error);
            this.attempts = attempts;
        }

        public int attempts() {
            return attempts;
        }
    }
}
