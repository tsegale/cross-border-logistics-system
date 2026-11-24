package com.freightflow.banking.hold.dto;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.hold.Hold;
import com.freightflow.banking.hold.HoldStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A hold: funds reserved on the payer account, then captured or released")
public record HoldResponse(
        UUID id,
        UUID payerAccountId,
        String payerAccountNumber,
        String reference,
        @Schema(description = "Charge amount, in the charge currency") BigDecimal amount,
        String currency,
        @Schema(description = "Amount reserved on the payer account, in its currency") BigDecimal payerAmount,
        String payerCurrency,
        @Schema(description = "Locked rate for a cross-currency hold: 1 payer currency = fxRate charge currency",
                nullable = true) BigDecimal fxRate,
        @Schema(description = "Where the locked rate came from (ADR 0034): ecb, ecb+cma-peg or cma-peg", nullable = true,
                example = "ecb+cma-peg") String rateSource,
        @Schema(description = "The day of the reference rate the locked rate came from", nullable = true)
        LocalDate rateValueDate,
        HoldStatus status,
        @Schema(nullable = true) BigDecimal capturedAmount,
        @Schema(nullable = true) BigDecimal capturedPayerAmount,
        @Schema(description = "The part of the capture that was duties and taxes (ADR 0036)", nullable = true)
        BigDecimal capturedDisbursementAmount,
        @Schema(nullable = true) UUID captureJournalEntryId,
        Instant expiresAt,
        @Schema(nullable = true) Instant closedAt,
        Instant createdAt
) {

    public static HoldResponse from(Hold hold, String payerAccountNumber) {
        return new HoldResponse(
                hold.getId(),
                hold.getPayerAccountId(),
                payerAccountNumber,
                hold.getReference(),
                MoneyRounding.roundToMinorUnits(hold.getAmount(), hold.getCurrency()),
                hold.getCurrency(),
                MoneyRounding.roundToMinorUnits(hold.getPayerAmount(), hold.getPayerCurrency()),
                hold.getPayerCurrency(),
                hold.getFxRate(),
                hold.getRateSource(),
                hold.getRateValueDate(),
                hold.getStatus(),
                hold.getCapturedAmount() == null ? null
                        : MoneyRounding.roundToMinorUnits(hold.getCapturedAmount(), hold.getCurrency()),
                hold.getCapturedPayerAmount() == null ? null
                        : MoneyRounding.roundToMinorUnits(hold.getCapturedPayerAmount(), hold.getPayerCurrency()),
                hold.getCapturedDisbursementAmount() == null ? null
                        : MoneyRounding.roundToMinorUnits(hold.getCapturedDisbursementAmount(), hold.getCurrency()),
                hold.getCaptureJournalEntryId(),
                hold.getExpiresAt(),
                hold.getClosedAt(),
                hold.getCreatedAt());
    }
}
