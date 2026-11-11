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
 * Recomputes every cached balance from the postings that built it and reports any difference, alongside a trial
 * balance per currency (ADR 0004). Since ADR 0010 a system account's balance has three parts, each checked
 * against its own postings: {@code accounts.balance} against the postings made with the row locked, every
 * {@code account_balance_shards} row against the postings made to that shard, and unlocked postings, which have
 * no cache to drift from. The per-currency trial balance covers all postings.
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
                LEFT JOIN postings p
                       ON p.account_id = a.id AND p.running_balance IS NOT NULL AND p.shard IS NULL
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

        // Sharded system postings (ADR 0010): every shard row must equal the sum of the postings made to it.
        jdbcTemplate.query(
                """
                SELECT a.id, a.account_number, a.currency, s.shard, s.balance,
                       COALESCE(SUM(p.amount), 0.0000) AS computed_balance
                FROM account_balance_shards s
                JOIN accounts a ON a.id = s.account_id
                LEFT JOIN postings p ON p.account_id = s.account_id AND p.shard = s.shard
                GROUP BY a.id, a.account_number, a.currency, s.shard, s.balance
                HAVING s.balance <> COALESCE(SUM(p.amount), 0.0000)
                ORDER BY a.account_number, s.shard
                """,
                rs -> {
                    String currency = rs.getString("currency");
                    BigDecimal shardBalance = rs.getBigDecimal("balance");
                    BigDecimal computed = rs.getBigDecimal("computed_balance");
                    discrepancies.add(new AccountDiscrepancy(
                            rs.getObject("id", UUID.class),
                            rs.getString("account_number") + "#shard" + rs.getInt("shard"),
                            currency,
                            MoneyRounding.roundToMinorUnits(shardBalance, currency),
                            MoneyRounding.roundToMinorUnits(computed, currency),
                            MoneyRounding.roundToMinorUnits(shardBalance.subtract(computed), currency)));
                });

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
