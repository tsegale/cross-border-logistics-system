package com.freightflow.banking.hold;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Errors of the hold lifecycle, each mapped to a problem type in docs/problems.md. */
public final class HoldExceptions {

    private HoldExceptions() {
    }

    public static class HoldNotFoundException extends RuntimeException {
        private final UUID holdId;

        public HoldNotFoundException(UUID holdId) {
            super("Hold '" + holdId + "' was not found");
            this.holdId = holdId;
        }

        public UUID getHoldId() {
            return holdId;
        }
    }

    /** Capture or release of a hold that is already captured, released or expired. */
    public static class HoldNotActiveException extends RuntimeException {
        private final UUID holdId;
        private final HoldStatus status;

        public HoldNotActiveException(UUID holdId, HoldStatus status) {
            super("Hold " + holdId + " is " + status + ", not ACTIVE");
            this.holdId = holdId;
            this.status = status;
        }

        public UUID getHoldId() {
            return holdId;
        }

        public HoldStatus getStatus() {
            return status;
        }
    }

    /** The hold passed its expiry; the sweeper releases it. */
    public static class HoldExpiredException extends RuntimeException {
        private final UUID holdId;
        private final Instant expiresAt;

        public HoldExpiredException(UUID holdId, Instant expiresAt) {
            super("Hold " + holdId + " expired at " + expiresAt);
            this.holdId = holdId;
            this.expiresAt = expiresAt;
        }

        public UUID getHoldId() {
            return holdId;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }
    }

    public static class CaptureExceedsHoldException extends RuntimeException {
        private final UUID holdId;
        private final BigDecimal holdAmount;
        private final BigDecimal requested;

        public CaptureExceedsHoldException(UUID holdId, BigDecimal holdAmount, BigDecimal requested) {
            super("Capture of " + requested + " exceeds hold " + holdId + " of " + holdAmount);
            this.holdId = holdId;
            this.holdAmount = holdAmount;
            this.requested = requested;
        }

        public UUID getHoldId() {
            return holdId;
        }

        public BigDecimal getHoldAmount() {
            return holdAmount;
        }

        public BigDecimal getRequested() {
            return requested;
        }
    }

    /** Payer is not a customer account, or the expiry is not in the allowed window. */
    public static class InvalidHoldException extends RuntimeException {
        public InvalidHoldException(String message) {
            super(message);
        }
    }
}
