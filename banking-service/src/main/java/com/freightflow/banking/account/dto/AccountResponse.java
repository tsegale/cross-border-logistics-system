package com.freightflow.banking.account.dto;

import com.freightflow.banking.account.Account;
import com.freightflow.banking.account.AccountStatus;
import com.freightflow.banking.account.AccountType;
import com.freightflow.banking.common.MoneyRounding;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Ledger account state and materialised balance")
public record AccountResponse(
        @Schema(description = "Time-ordered UUIDv7 identifier", example = "01930000-0000-7000-8000-000000001000")
        UUID id,
        @Schema(description = "Human account number with ISO 7064 MOD 97-10 check digits", example = "FF49NAD000001000")
        String accountNumber,
        @Schema(description = "Holder reference", example = "CUST-NAM-WALVIS-001")
        String holderReference,
        @Schema(description = "Account type", example = "CUSTOMER")
        AccountType type,
        @Schema(description = "ISO 4217 currency code", example = "NAD")
        String currency,
        @Schema(description = "Account lifecycle status", example = "ACTIVE")
        AccountStatus status,
        @Schema(description = "Ledger balance: the sum of the account's postings, rounded HALF_EVEN", example = "12500.00")
        BigDecimal ledgerBalance,
        @Schema(description = "Ledger balance minus active holds: what a transfer or a new hold may use", example = "11975.25")
        BigDecimal availableBalance,
        @Schema(description = "Creation timestamp (UTC)", example = "2026-11-07T18:00:00Z")
        Instant createdAt,
        @Schema(description = "Last balance update timestamp (UTC)", example = "2026-11-07T18:05:00Z")
        Instant updatedAt
) {

    public static AccountResponse from(Account account) {
        return from(account, account.getBalance());
    }

    /** {@code balance} is passed in for system accounts, whose balance is derived (ADR 0010). They hold nothing. */
    public static AccountResponse from(Account account, BigDecimal balance) {
        BigDecimal held = account.getHeldAmount() == null ? BigDecimal.ZERO : account.getHeldAmount();
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getHolderReference(),
                account.getAccountType(),
                account.getCurrency(),
                account.getStatus(),
                MoneyRounding.roundToMinorUnits(balance, account.getCurrency()),
                MoneyRounding.roundToMinorUnits(balance.subtract(held), account.getCurrency()),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
