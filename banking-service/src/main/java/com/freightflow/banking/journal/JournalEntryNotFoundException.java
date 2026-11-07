package com.freightflow.banking.journal;

import java.util.UUID;

public class JournalEntryNotFoundException extends RuntimeException {

    private final UUID journalEntryId;

    public JournalEntryNotFoundException(UUID journalEntryId) {
        super("Journal entry '" + journalEntryId + "' was not found");
        this.journalEntryId = journalEntryId;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }
}
