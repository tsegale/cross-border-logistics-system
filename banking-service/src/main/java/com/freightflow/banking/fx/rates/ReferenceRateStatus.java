package com.freightflow.banking.fx.rates;

/** Whether a reference rate may price anything (ADR 0034). */
public enum ReferenceRateStatus {
    /** Passed every check, or two operators confirmed it: prices quotes and holds. */
    CONFIRMED,
    /** Failed a check: never used until two operators confirm it. */
    QUARANTINED,
    /** Two operators agreed it is wrong: never used. */
    REJECTED
}
