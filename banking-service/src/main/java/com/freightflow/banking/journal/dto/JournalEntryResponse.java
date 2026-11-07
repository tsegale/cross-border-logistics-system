package com.freightflow.banking.journal.dto;

import com.freightflow.banking.journal.JournalEntry;
import com.freightflow.banking.journal.JournalEntryType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Immutable double-entry journal entry with balanced postings")
public record JournalEntryResponse(
        UUID id,
        JournalEntryType entryType,
        String reference,
        UUID reversesEntryId,
        UUID fxQuoteId,
        BigDecimal fxRate,
        Instant createdAt,
        List<PostingResponse> postings
) {

    public static JournalEntryResponse from(JournalEntry entry, List<PostingResponse> postings) {
        return new JournalEntryResponse(
                entry.getId(),
                entry.getEntryType(),
                entry.getReference(),
                entry.getReversesEntryId(),
                entry.getFxQuoteId(),
                entry.getFxRate(),
                entry.getCreatedAt(),
                postings
        );
    }
}
