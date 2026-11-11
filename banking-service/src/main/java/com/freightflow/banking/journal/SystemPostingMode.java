package com.freightflow.banking.journal;

/**
 * How postings to system accounts (CLEARING, FX_POSITION, FREIGHT_REVENUE) are applied (ADR 0010). Customer
 * accounts are always locked and updated in place: their balance may not go negative.
 */
public enum SystemPostingMode {
    /** Phase 1: the system row is locked and its balance updated, like a customer row. One hot row per currency. */
    LOCKED,
    /** No lock and no balance update: the posting is appended with no running balance; the balance is derived. */
    UNLOCKED,
    /** One of N shard rows is locked and updated, chosen by a hash of the journal entry id. */
    SHARDED
}
