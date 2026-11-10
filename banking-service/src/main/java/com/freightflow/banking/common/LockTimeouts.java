package com.freightflow.banking.common;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Runs one locking statement under its own PostgreSQL {@code lock_timeout} and restores the previous value
 * afterwards.
 *
 * <p>{@code SET LOCAL lock_timeout} lasts until the end of the transaction, so a timeout meant for one lock
 * would otherwise cap every later lock in the same transaction. If the statement fails the transaction is
 * aborted anyway, so the restore only runs on success.
 */
public final class LockTimeouts {

    private LockTimeouts() {
    }

    public static <T> T withLockTimeout(JdbcTemplate jdbc, Duration timeout, Supplier<T> lockingStatement) {
        String previous = jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
        long millis = Math.max(timeout.toMillis(), 1L);
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class, millis + "ms");
        T result = lockingStatement.get();
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class, previous);
        return result;
    }
}
