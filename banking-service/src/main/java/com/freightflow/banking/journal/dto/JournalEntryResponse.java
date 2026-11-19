package com.freightflow.banking.journal.dto;

import com.freightflow.banking.security.Actor;
import com.freightflow.banking.security.Caller;
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
        List<PostingResponse> postings,
        @Schema(description = "Who made the entry (ADR 0020): for a deposit or reversal, the operator who approved it. "
                + "Shown to operators and auditors only", nullable = true)
        Actor actor,
        @Schema(description = "The four-eyes approval that posted a deposit or reversal (ADR 0021); it names the operator "
                + "who requested it. Shown to operators and auditors only", nullable = true)
        UUID approvalId
) {

    /** What a customer sees: the same entry without who made it. */
    public JournalEntryResponse forCaller(Caller caller) {
        return caller.seesEverything() ? this
                : new JournalEntryResponse(id, entryType, reference, reversesEntryId, fxQuoteId, fxRate, createdAt,
                postings, null, null);
    }

    public static JournalEntryResponse from(JournalEntry entry, List<PostingResponse> postings) {
        return new JournalEntryResponse(
                entry.getId(),
                entry.getEntryType(),
                entry.getReference(),
                entry.getReversesEntryId(),
                entry.getFxQuoteId(),
                entry.getFxRate(),
                entry.getCreatedAt(),
                postings,
                entry.getActor(),
                entry.getApprovalId()
        );
    }
}
