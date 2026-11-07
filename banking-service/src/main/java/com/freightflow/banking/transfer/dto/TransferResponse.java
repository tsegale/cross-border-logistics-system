package com.freightflow.banking.transfer.dto;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.journal.dto.PostingResponse;
import com.freightflow.banking.transfer.Transfer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Executed ledger transfer and its double-entry postings")
public record TransferResponse(
        UUID id,
        UUID journalEntryId,
        UUID sourceAccountId,
        String sourceAccountNumber,
        UUID targetAccountId,
        String targetAccountNumber,
        BigDecimal sourceAmount,
        String sourceCurrency,
        BigDecimal targetAmount,
        String targetCurrency,
        String reference,
        UUID fxQuoteId,
        BigDecimal fxRate,
        Instant createdAt,
        List<PostingResponse> postings
) {

    public static TransferResponse from(
            Transfer transfer,
            String sourceAccountNumber,
            String targetAccountNumber,
            List<PostingResponse> postings) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getJournalEntryId(),
                transfer.getSourceAccountId(),
                sourceAccountNumber,
                transfer.getTargetAccountId(),
                targetAccountNumber,
                MoneyRounding.roundToMinorUnits(transfer.getSourceAmount(), transfer.getSourceCurrency()),
                transfer.getSourceCurrency(),
                MoneyRounding.roundToMinorUnits(transfer.getTargetAmount(), transfer.getTargetCurrency()),
                transfer.getTargetCurrency(),
                transfer.getReference(),
                transfer.getFxQuoteId(),
                transfer.getFxRate(),
                transfer.getCreatedAt(),
                postings
        );
    }
}
