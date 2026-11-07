package com.freightflow.banking.reconciliation;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.reconciliation.dto.ReconciliationReportResponse;
import com.freightflow.banking.reconciliation.dto.ReconciliationReportResponse.AccountDiscrepancy;
import com.freightflow.banking.reconciliation.dto.ReconciliationReportResponse.CurrencyTrialBalance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recomputes every account's balance from {@code SUM(postings.amount)} and compares it against the
 * materialised {@code accounts.balance} column, alongside a trial balance per currency (ADR 0004).
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ReconciliationService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReportResponse reconcile() {
        List<AccountCheckRow> accountRows = jdbcTemplate.query(
                """
                SELECT a.id,
                       a.account_number,
                       a.currency,
                       a.balance AS materialised_balance,
                       COALESCE(SUM(p.amount), 0.0000) AS computed_balance
                FROM accounts a
                LEFT JOIN postings p ON p.account_id = a.id
                GROUP BY a.id, a.account_number, a.currency, a.balance
                ORDER BY a.account_number ASC
                """,
                (rs, rowNum) -> new AccountCheckRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("account_number"),
                        rs.getString("currency"),
                        rs.getBigDecimal("materialised_balance"),
                        rs.getBigDecimal("computed_balance")
                )
        );

        List<AccountDiscrepancy> discrepancies = new ArrayList<>();
        for (AccountCheckRow row : accountRows) {
            BigDecimal diff = row.materialisedBalance().subtract(row.computedBalance());
            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                discrepancies.add(new AccountDiscrepancy(
                        row.id(),
                        row.accountNumber(),
                        row.currency(),
                        MoneyRounding.roundToMinorUnits(row.materialisedBalance(), row.currency()),
                        MoneyRounding.roundToMinorUnits(row.computedBalance(), row.currency()),
                        MoneyRounding.roundToMinorUnits(diff, row.currency())
                ));
            }
        }

        List<CurrencyTrialBalance> trialBalances = jdbcTemplate.query(
                """
                SELECT c.currency,
                       COUNT(p.id) AS posting_count,
                       COALESCE(SUM(p.amount), 0.0000) AS net_sum
                FROM (SELECT DISTINCT currency FROM accounts) c
                LEFT JOIN postings p ON p.currency = c.currency
                GROUP BY c.currency
                ORDER BY c.currency ASC
                """,
                (rs, rowNum) -> {
                    String currency = rs.getString("currency");
                    long postingCount = rs.getLong("posting_count");
                    BigDecimal netSum = rs.getBigDecimal("net_sum");
                    boolean currencyBalanced = netSum.compareTo(BigDecimal.ZERO) == 0;
                    return new CurrencyTrialBalance(
                            currency,
                            postingCount,
                            MoneyRounding.roundToMinorUnits(netSum, currency),
                            currencyBalanced
                    );
                }
        );

        boolean allCurrenciesBalanced = trialBalances.stream().allMatch(CurrencyTrialBalance::balanced);
        boolean overallBalanced = discrepancies.isEmpty() && allCurrenciesBalanced;

        return new ReconciliationReportResponse(
                clock.instant(),
                accountRows.size(),
                overallBalanced,
                List.copyOf(discrepancies),
                trialBalances
        );
    }

    private record AccountCheckRow(
            UUID id,
            String accountNumber,
            String currency,
            BigDecimal materialisedBalance,
            BigDecimal computedBalance
    ) {
    }
}
