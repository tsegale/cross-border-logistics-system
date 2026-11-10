package com.freightflow.banking.common;

import java.sql.SQLException;
import java.util.Optional;

/** Finds the PostgreSQL SQLSTATE behind a Spring, Hibernate or JDBC exception chain. */
public final class SqlStates {

    /** {@code lock_not_available}: a lock was not granted within {@code lock_timeout}. */
    public static final String LOCK_NOT_AVAILABLE = "55P03";
    /** {@code deadlock_detected}. */
    public static final String DEADLOCK_DETECTED = "40P01";
    /** {@code serialization_failure}. */
    public static final String SERIALIZATION_FAILURE = "40001";

    private SqlStates() {
    }

    public static Optional<String> of(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return Optional.of(sql.getSQLState());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Optional.empty();
    }
}
