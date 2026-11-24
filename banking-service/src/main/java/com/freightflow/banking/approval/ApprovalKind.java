package com.freightflow.banking.approval;

/** What an approved request does (ADR 0021): posts an entry, or decides a quarantined rate. */
public enum ApprovalKind {
    /** A credit to a customer account from the currency's CLEARING account. */
    DEPOSIT,
    /** A compensating entry with the opposite postings of an existing one. */
    REVERSAL,
    /** A quarantined reference rate made usable, or rejected (ADR 0034). Posts no entry. */
    RATE_CONFIRMATION
}
