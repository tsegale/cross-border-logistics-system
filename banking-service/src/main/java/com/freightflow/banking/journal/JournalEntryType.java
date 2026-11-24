package com.freightflow.banking.journal;

public enum JournalEntryType {
    DEPOSIT,
    TRANSFER,
    FX_TRANSFER,
    REVERSAL,
    /**
     * Capture of a hold: payer to FREIGHT_REVENUE, and to CUSTOMS_DISBURSEMENTS for the duties and taxes collected
     * with it, through FX_POSITION when the currencies differ.
     */
    CAPTURE,
    /**
     * Duties and taxes paid to the customs authority out of CUSTOMS_DISBURSEMENTS, and the difference between the
     * assessment and the estimate charged to or refunded to the payer (ADR 0036).
     */
    CUSTOMS_SETTLEMENT
}
