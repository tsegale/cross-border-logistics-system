package com.freightflow.banking.journal.dto;

import com.freightflow.banking.journal.JournalEntryType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Account statement line with running balance after the posting")
public record StatementLineResponse(
        UUID postingId,
        UUID journalEntryId,
        JournalEntryType entryType,
        String reference,
        BigDecimal amount,
        String currency,
        BigDecimal runningBalance,
        Instant createdAt
) {
}
