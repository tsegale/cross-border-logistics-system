package com.freightflow.banking.journal.dto;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.journal.Posting;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Single signed posting within a journal entry")
public record PostingResponse(
        UUID id,
        UUID accountId,
        String accountNumber,
        BigDecimal amount,
        String currency,
        BigDecimal runningBalance,
        Instant createdAt
) {

    public static PostingResponse from(Posting posting, String accountNumber) {
        return new PostingResponse(
                posting.getId(),
                posting.getAccountId(),
                accountNumber,
                MoneyRounding.roundToMinorUnits(posting.getAmount(), posting.getCurrency()),
                posting.getCurrency(),
                MoneyRounding.roundToMinorUnits(posting.getRunningBalance(), posting.getCurrency()),
                posting.getCreatedAt()
        );
    }
}
