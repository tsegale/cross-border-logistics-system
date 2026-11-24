package com.freightflow.banking.fx.rates;

import java.time.LocalDate;
import java.util.UUID;

/** Errors of live reference rates (ADR 0034), each mapped to a problem type in docs/problems.md. */
public final class ReferenceRateExceptions {

    private ReferenceRateExceptions() {
    }

    /** The newest confirmed rate is older than the policy allows, or there is none: FX is refused, not mispriced. */
    public static class FxRatesStaleException extends RuntimeException {
        private final String currencyPair;
        private final LocalDate valueDate;
        private final LocalDate expectedValueDate;
        private final int maxAgeWorkingDays;

        public FxRatesStaleException(String currencyPair, LocalDate valueDate, LocalDate expectedValueDate,
                                     int maxAgeWorkingDays) {
            super(valueDate == null
                    ? "No confirmed reference rate for " + currencyPair + " yet"
                    : "The newest confirmed reference rate for " + currencyPair + " is for " + valueDate
                            + "; one for " + expectedValueDate + " is due, and rates more than " + maxAgeWorkingDays
                            + " working days behind it are not used");
            this.currencyPair = currencyPair;
            this.valueDate = valueDate;
            this.expectedValueDate = expectedValueDate;
            this.maxAgeWorkingDays = maxAgeWorkingDays;
        }

        public String getCurrencyPair() {
            return currencyPair;
        }

        public LocalDate getValueDate() {
            return valueDate;
        }

        public LocalDate getExpectedValueDate() {
            return expectedValueDate;
        }

        public int getMaxAgeWorkingDays() {
            return maxAgeWorkingDays;
        }
    }

    public static class ReferenceRateNotFoundException extends RuntimeException {
        private final UUID referenceRateId;

        public ReferenceRateNotFoundException(UUID referenceRateId) {
            super("Reference rate '" + referenceRateId + "' was not found");
            this.referenceRateId = referenceRateId;
        }

        public UUID getReferenceRateId() {
            return referenceRateId;
        }
    }

    /** Only a quarantined rate is confirmed or rejected, once. */
    public static class ReferenceRateNotQuarantinedException extends RuntimeException {
        private final UUID referenceRateId;
        private final ReferenceRateStatus status;

        public ReferenceRateNotQuarantinedException(UUID referenceRateId, ReferenceRateStatus status) {
            super("Reference rate " + referenceRateId + " is " + status + ", not QUARANTINED");
            this.referenceRateId = referenceRateId;
            this.status = status;
        }

        public UUID getReferenceRateId() {
            return referenceRateId;
        }

        public ReferenceRateStatus getStatus() {
            return status;
        }
    }

    /** A list of reference rates asked for with a limit outside 1..100. */
    public static class InvalidReferenceRateQueryException extends RuntimeException {
        public InvalidReferenceRateQueryException(String message) {
            super(message);
        }
    }

    /** A second confirmation request for a rate that already has one waiting. */
    public static class RateConfirmationAlreadyRequestedException extends RuntimeException {
        private final UUID referenceRateId;

        public RateConfirmationAlreadyRequestedException(UUID referenceRateId) {
            super("Reference rate " + referenceRateId + " already has a confirmation waiting for approval");
            this.referenceRateId = referenceRateId;
        }

        public UUID getReferenceRateId() {
            return referenceRateId;
        }
    }
}
