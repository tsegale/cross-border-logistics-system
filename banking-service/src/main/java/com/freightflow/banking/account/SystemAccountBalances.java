package com.freightflow.banking.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Current balance of a system account (ADR 0010): the part posted while the row was locked, plus its shard rows,
 * plus its unlocked postings. Exact, and read without taking a lock. The unlocked part is an index-only scan over
 * {@code idx_postings_unlocked_by_account}, so it grows with the number of unlocked postings.
 */
@Component
public class SystemAccountBalances {

    private final JdbcTemplate jdbcTemplate;

    public SystemAccountBalances(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BigDecimal current(UUID accountId) {
        BigDecimal balance = jdbcTemplate.queryForObject(
                """
                SELECT a.balance
                       + COALESCE((SELECT SUM(s.balance) FROM account_balance_shards s WHERE s.account_id = a.id), 0)
                       + COALESCE((SELECT SUM(p.amount) FROM postings p
                                   WHERE p.account_id = a.id AND p.running_balance IS NULL), 0)
                FROM accounts a
                WHERE a.id = ?
                """,
                BigDecimal.class,
                accountId);
        if (balance == null) {
            throw new AccountNotFoundException(accountId);
        }
        return balance;
    }

    public BigDecimal balanceOf(Account account) {
        return account.getAccountType().isCustomer() ? account.getBalance() : current(account.getId());
    }
}
