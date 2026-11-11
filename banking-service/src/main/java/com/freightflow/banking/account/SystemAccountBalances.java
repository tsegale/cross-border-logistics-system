package com.freightflow.banking.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Current balance of a system account (ADR 0010): the part posted while the row was locked, plus its shard rows,
 * plus its unlocked postings. Exact, and read without taking a lock. The unlocked part is the latest checkpoint
 * ({@link SystemBalanceCheckpoints}) plus the postings of transactions at or above its horizon: an index-only range
 * scan over {@code idx_postings_unlocked_by_account_txid} that stays as short as the time since the last roll-up.
 *
 * <p>The checkpoint and the postings are read in two statements. That is still exact: every transaction below the
 * checkpoint's horizon had finished before the checkpoint was written, so the second statement sees all postings at
 * or above it and none below.
 */
@Component
public class SystemAccountBalances {

    private final JdbcTemplate jdbcTemplate;

    public SystemAccountBalances(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BigDecimal current(UUID accountId) {
        List<Checkpoint> latest = jdbcTemplate.query("""
                SELECT balance, covered_below::text AS covered_below FROM system_balance_checkpoints
                WHERE account_id = ? ORDER BY covered_below DESC LIMIT 1
                """, (rs, row) -> new Checkpoint(rs.getBigDecimal("balance"), rs.getString("covered_below")), accountId);
        BigDecimal balance;
        if (latest.isEmpty()) {
            balance = jdbcTemplate.queryForObject("""
                    SELECT a.balance
                           + COALESCE((SELECT SUM(s.balance) FROM account_balance_shards s WHERE s.account_id = a.id), 0)
                           + COALESCE((SELECT SUM(p.amount) FROM postings p
                                       WHERE p.account_id = a.id AND p.running_balance IS NULL), 0)
                    FROM accounts a
                    WHERE a.id = ?
                    """, BigDecimal.class, accountId);
        } else {
            Checkpoint checkpoint = latest.getFirst();
            balance = jdbcTemplate.queryForObject("""
                    SELECT a.balance
                           + COALESCE((SELECT SUM(s.balance) FROM account_balance_shards s WHERE s.account_id = a.id), 0)
                           + ?
                           + COALESCE((SELECT SUM(p.amount) FROM postings p
                                       WHERE p.account_id = a.id AND p.running_balance IS NULL
                                         AND p.txid >= ?::xid8), 0)
                    FROM accounts a
                    WHERE a.id = ?
                    """, BigDecimal.class, checkpoint.balance(), checkpoint.coveredBelow(), accountId);
        }
        if (balance == null) {
            throw new AccountNotFoundException(accountId);
        }
        return balance;
    }

    private record Checkpoint(BigDecimal balance, String coveredBelow) {
    }

    public BigDecimal balanceOf(Account account) {
        return account.getAccountType().isCustomer() ? account.getBalance() : current(account.getId());
    }
}
