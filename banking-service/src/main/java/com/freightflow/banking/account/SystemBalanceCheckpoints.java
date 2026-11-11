package com.freightflow.banking.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Rolls the unlocked postings of each system account into an append-only checkpoint (ADR 0010), so its balance is
 * the latest checkpoint plus the postings since, instead of a sum over every posting it ever had.
 *
 * <p>The roll-up statement is {@code sql/system-balance-checkpoint.sql}. It covers exactly the postings of
 * transactions below the current snapshot's xmin, which have all finished, so a checkpoint is exact however many
 * writers are in flight. Every instance runs the job: a transaction-scoped advisory lock per account lets one of
 * them do the work, and the unique (account, horizon) key makes a duplicate a no-op anyway.
 */
@Component
public class SystemBalanceCheckpoints {

    private static final Logger log = LoggerFactory.getLogger(SystemBalanceCheckpoints.class);
    private static final String ROLL_UP = load("sql/system-balance-checkpoint.sql");

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;

    public SystemBalanceCheckpoints(JdbcTemplate jdbcTemplate, TransactionTemplate transactions, Clock clock,
                                    @Value("${freightflow.banking.system-accounts.checkpoints-enabled:true}")
                                    boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.named = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.transactions = transactions;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${freightflow.banking.system-accounts.checkpoint-interval:60s}",
            initialDelayString = "${freightflow.banking.system-accounts.checkpoint-interval:60s}")
    public void scheduledRollUp() {
        if (enabled) {
            rollUpAll();
        }
    }

    /** Checkpoints every system account that has new finished postings. Returns how many checkpoints it wrote. */
    public int rollUpAll() {
        List<UUID> systemAccounts = jdbcTemplate.queryForList(
                "SELECT id FROM accounts WHERE account_type <> 'CUSTOMER' ORDER BY id", UUID.class);
        int written = 0;
        for (UUID accountId : systemAccounts) {
            try {
                if (rollUp(accountId)) {
                    written++;
                }
            } catch (RuntimeException ex) {
                // One account failing must not stop the others; the next run tries it again.
                log.error("Could not checkpoint system account {}", accountId, ex);
            }
        }
        return written;
    }

    /** One roll-up of one account in its own transaction. Returns true if it wrote a checkpoint. */
    public boolean rollUp(UUID accountId) {
        Boolean written = transactions.execute(status -> {
            Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                    Boolean.class, "system-balance-checkpoint:" + accountId);
            if (!Boolean.TRUE.equals(locked)) {
                return false; // another instance is checkpointing this account right now
            }
            int rows = named.update(ROLL_UP, new MapSqlParameterSource()
                    .addValue("accountId", accountId)
                    .addValue("id", UUID.randomUUID())
                    .addValue("createdAt", Timestamp.from(clock.instant())));
            return rows == 1;
        });
        return Boolean.TRUE.equals(written);
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + path, ex);
        }
    }
}
