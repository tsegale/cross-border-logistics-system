package com.freightflow.banking.journal;

public enum JournalEntryType {
    DEPOSIT,
    TRANSFER,
    FX_TRANSFER,
    REVERSAL,
    /** Capture of a hold: payer to FREIGHT_REVENUE, through FX_POSITION when the currencies differ. */
    CAPTURE
}
