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
 * {@code account_balance_shards} row against the postings made to that shard, and each account's latest
 * {@code system_balance_checkpoints} row against the unlocked postings it covers. The per-currency trial balance
 * covers all postings.
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

        // Checkpoints (ADR 0010): an account's latest checkpoint must equal the sum of the unlocked postings it covers.
        jdbcTemplate.query(
                """
                SELECT a.id, a.account_number, a.currency, c.balance,
                       COALESCE((SELECT SUM(p.amount) FROM postings p
                                 WHERE p.account_id = a.id AND p.running_balance IS NULL
                                   AND (p.txid IS NULL OR p.txid < c.covered_below)), 0.0000) AS computed_balance
                FROM accounts a
                JOIN LATERAL (SELECT k.balance, k.covered_below FROM system_balance_checkpoints k
                              WHERE k.account_id = a.id ORDER BY k.covered_below DESC LIMIT 1) c ON true
                ORDER BY a.account_number
                """,
                rs -> {
                    String currency = rs.getString("currency");
                    BigDecimal checkpoint = rs.getBigDecimal("balance");
                    BigDecimal computed = rs.getBigDecimal("computed_balance");
                    if (checkpoint.compareTo(computed) != 0) {
                        discrepancies.add(new AccountDiscrepancy(
                                rs.getObject("id", UUID.class),
                                rs.getString("account_number") + "#checkpoint",
                                currency,
                                MoneyRounding.roundToMinorUnits(checkpoint, currency),
                                MoneyRounding.roundToMinorUnits(computed, currency),
                                MoneyRounding.roundToMinorUnits(checkpoint.subtract(computed), currency)));
                    }
                });

        // Holds (ADR 0011): an account's cached held amount must equal the sum of its active holds.
        jdbcTemplate.query(
                """
                SELECT a.id, a.account_number, a.currency, a.held_amount,
                       COALESCE(SUM(h.payer_amount), 0.0000) AS active_holds
                FROM accounts a
                LEFT JOIN holds h ON h.payer_account_id = a.id AND h.status = 'ACTIVE'
                GROUP BY a.id, a.account_number, a.currency, a.held_amount
                HAVING a.held_amount <> COALESCE(SUM(h.payer_amount), 0.0000)
                ORDER BY a.account_number
                """,
                rs -> {
                    String currency = rs.getString("currency");
                    BigDecimal held = rs.getBigDecimal("held_amount");
                    BigDecimal active = rs.getBigDecimal("active_holds");
                    discrepancies.add(new AccountDiscrepancy(
                            rs.getObject("id", UUID.class),
                            rs.getString("account_number") + "#held",
                            currency,
                            MoneyRounding.roundToMinorUnits(held, currency),
                            MoneyRounding.roundToMinorUnits(active, currency),
                            MoneyRounding.roundToMinorUnits(held.subtract(active), currency)));
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
