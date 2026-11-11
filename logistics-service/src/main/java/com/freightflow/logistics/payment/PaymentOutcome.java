package com.freightflow.logistics.payment;

import java.time.Instant;
import java.util.UUID;

/** What one attempt of a payment command achieved (ADR 0012). */
public sealed interface PaymentOutcome {

    /**
     * banking did what was asked, now or on an earlier attempt whose response was lost. {@code holdExpiresAt} is the
     * hold's expiry after an AUTHORISE or EXTEND, and null when there is nothing to record (an extension of a hold
     * that was captured or released in the meantime).
     */
    record Succeeded(UUID holdId, Instant holdExpiresAt) implements PaymentOutcome {
    }

    /** banking said no for a business reason (4xx): the same request will never succeed, so it is not retried. */
    record Refused(String problemType, String detail) implements PaymentOutcome {
    }

    /**
     * Timeout, connection reset, 5xx or a retryable conflict: banking may or may not have acted. The command is
     * retried with the same idempotency key. {@code reachedBanking} is false when the call was never made (circuit
     * open, bulkhead full), which does not use up an attempt.
     */
    record Unknown(String reason, boolean reachedBanking) implements PaymentOutcome {
    }
}
