package com.freightflow.banking.approval.dto;

import com.freightflow.banking.approval.ApprovalKind;
import com.freightflow.banking.approval.ApprovalRequest;
import com.freightflow.banking.approval.ApprovalStatus;
import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.security.Actor;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A four-eyes approval request (ADR 0021): who asked, who decided, and the entry it posted")
public record ApprovalResponse(
        UUID id,
        ApprovalKind kind,
        ApprovalStatus status,
        @Schema(description = "DEPOSIT: the customer account number", nullable = true)
        String account,
        @Schema(description = "DEPOSIT: the amount", nullable = true)
        BigDecimal amount,
        @Schema(nullable = true)
        String currency,
        @Schema(nullable = true)
        String reference,
        @Schema(description = "REVERSAL: the entry to reverse", nullable = true)
        UUID journalEntryId,
        @Schema(nullable = true)
        String reason,
        @Schema(description = "The maker")
        Actor requestedBy,
        Instant requestedAt,
        Instant expiresAt,
        @Schema(description = "The checker; null while pending and for an expired request", nullable = true)
        Actor decidedBy,
        @Schema(nullable = true)
        Instant decidedAt,
        @Schema(nullable = true)
        String decisionComment,
        @Schema(description = "The journal entry the approval posted", nullable = true)
        UUID postedEntryId
) {

    public static ApprovalResponse from(ApprovalRequest request, String accountNumber) {
        return new ApprovalResponse(
                request.getId(),
                request.getKind(),
                request.getStatus(),
                accountNumber,
                request.getAmount() == null ? null
                        : MoneyRounding.roundToMinorUnits(request.getAmount(), request.getCurrency()),
                request.getCurrency(),
                request.getReference(),
                request.getJournalEntryId(),
                request.getReason(),
                request.getRequestedBy(),
                request.getRequestedAt(),
                request.getExpiresAt(),
                request.getDecidedBy(),
                request.getDecidedAt(),
                request.getDecisionComment(),
                request.getPostedEntryId()
        );
    }
}
