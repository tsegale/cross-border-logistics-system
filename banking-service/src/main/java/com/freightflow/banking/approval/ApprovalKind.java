package com.freightflow.banking.approval;

/** What an approved request posts (ADR 0021). */
public enum ApprovalKind {
    /** A credit to a customer account from the currency's CLEARING account. */
    DEPOSIT,
    /** A compensating entry with the opposite postings of an existing one. */
    REVERSAL
}
