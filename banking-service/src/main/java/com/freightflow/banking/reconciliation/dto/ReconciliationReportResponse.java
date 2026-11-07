package com.freightflow.banking.reconciliation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationReportResponse(
        Instant checkedAt,
        int accountsChecked,
        boolean balanced,
        List<AccountDiscrepancy> discrepancies,
        List<CurrencyTrialBalance> trialBalances
) {

    public record AccountDiscrepancy(
            UUID accountId,
            String accountNumber,
            String currency,
            BigDecimal materialisedBalance,
            BigDecimal computedBalance,
            BigDecimal difference
    ) {
    }

    public record CurrencyTrialBalance(
            String currency,
            long postingCount,
            BigDecimal netSum,
            boolean balanced
    ) {
    }
}
