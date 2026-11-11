package com.freightflow.logistics.payment;

import java.util.UUID;

/** What one attempt of a payment command achieved (ADR 0012). */
public sealed interface PaymentOutcome {

    /** banking did what was asked, now or on an earlier attempt whose response was lost. */
    record Succeeded(UUID holdId) implements PaymentOutcome {
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
