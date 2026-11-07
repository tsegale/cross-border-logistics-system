package com.freightflow.banking.journal;

import java.util.UUID;

public class JournalEntryAlreadyReversedException extends RuntimeException {

    private final UUID journalEntryId;

    public JournalEntryAlreadyReversedException(UUID journalEntryId, String message) {
        super(message);
        this.journalEntryId = journalEntryId;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }
}
