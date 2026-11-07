package com.freightflow.banking.journal;

public class UnbalancedJournalEntryException extends RuntimeException {

    public UnbalancedJournalEntryException(String message) {
        super(message);
    }
}
