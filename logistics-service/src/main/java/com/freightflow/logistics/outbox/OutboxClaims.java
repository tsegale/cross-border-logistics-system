package com.freightflow.logistics.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * How every outbox in logistics hands rows to its relay (ADR 0025): the payment commands for banking (ADR 0012) and
 * the domain events for Kafka share this one mechanism, so its guarantees are written and tested once.
 *
 * <ul>
 *   <li><b>Claim with a lease.</b> Due rows are taken with {@code FOR UPDATE SKIP LOCKED}, and {@code
 *       next_attempt_at} moves out by the lease in the same statement, so relays on several instances claim side by
 *       side without blocking each other or picking the same row. The send runs after the claim committed, outside
 *       any transaction; if the relay dies mid-send, the lease runs out and another relay takes the row again.</li>
 *   <li><b>Head of each aggregate only</b>, if the table names an aggregate and an order column: a row is claimable
 *       only while no earlier row of its aggregate is still pending. One aggregate's rows then go out one at a time,
 *       in order, whichever instance relays them.</li>
 *   <li>{@code attempts} counts claims, so a relay can give up or back off.</li>
 * </ul>
 *
 * <p>Table and column names come from {@link Table} constants in code, never from input, and are checked to be plain
 * identifiers.
 */
@Component
public class OutboxClaims {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_]+");
    private static final String ROW = "{row}";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public OutboxClaims(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * One outbox table. The table needs {@code id}, {@code attempts} and {@code next_attempt_at} columns.
     *
     * @param table           the table name
     * @param pending         SQL predicate of a row still to be relayed, its columns qualified with {@code {row}},
     *                        e.g. {@code {row}.status = 'PENDING'}
     * @param aggregateColumn rows of one aggregate go out in order; null if order does not matter
     * @param orderColumn     the order within an aggregate; null if order does not matter
     */
    public record Table(String table, String pending, String aggregateColumn, String orderColumn) {

        public Table {
            requireIdentifier(table);
            if (pending == null || !pending.contains(ROW)) {
                throw new IllegalArgumentException("pending must qualify its columns with " + ROW + ": " + pending);
            }
            if ((aggregateColumn == null) != (orderColumn == null)) {
                throw new IllegalArgumentException("aggregateColumn and orderColumn go together");
            }
            if (aggregateColumn != null) {
                requireIdentifier(aggregateColumn);
                requireIdentifier(orderColumn);
            }
        }

        public static Table unordered(String table, String pending) {
            return new Table(table, pending, null, null);
        }

        public static Table orderedBy(String table, String pending, String aggregateColumn, String orderColumn) {
            return new Table(table, pending, aggregateColumn, orderColumn);
        }

        /** The predicate for a row aliased {@code alias}. */
        String pending(String alias) {
            return pending.replace(ROW, alias);
        }

        private static void requireIdentifier(String name) {
            if (name == null || !IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("Not a plain SQL identifier: " + name);
            }
        }
    }

    /**
     * Claims up to {@code limit} due rows and leases them. {@code returning} lists the columns the relay needs; the
     * mapper reads them.
     */
    @Transactional
    public <T> List<T> claim(Table table, int limit, Duration lease, String returning, RowMapper<T> mapper) {
        Instant now = clock.instant();
        String headOnly = table.aggregateColumn() == null ? "" : """
                      AND NOT EXISTS (
                          SELECT 1 FROM %1$s earlier
                          WHERE earlier.%2$s = o.%2$s AND earlier.%3$s < o.%3$s AND (%4$s))
                """.formatted(table.table(), table.aggregateColumn(), table.orderColumn(), table.pending("earlier"));
        String sql = """
                UPDATE %1$s SET next_attempt_at = ?, attempts = attempts + 1
                WHERE id IN (
                    SELECT o.id FROM %1$s o
                    WHERE (%2$s) AND o.next_attempt_at <= ?
                %3$s
                    ORDER BY o.next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING %4$s
                """.formatted(table.table(), table.pending("o"), headOnly, returning);
        return jdbcTemplate.query(sql, mapper, Timestamp.from(now.plus(lease)), Timestamp.from(now), limit);
    }

    /** Rows still to be relayed. */
    public long backlog(Table table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table.table() + " WHERE " + table.pending(table.table()), Long.class);
        return count == null ? 0 : count;
    }

    /** Seconds since the oldest pending row's {@code timeColumn}; 0 when nothing waits. */
    public double oldestPendingAgeSeconds(Table table, String timeColumn) {
        Table.requireIdentifier(timeColumn);
        Timestamp oldest = jdbcTemplate.queryForObject("SELECT min(" + timeColumn + ") FROM " + table.table()
                + " WHERE " + table.pending(table.table()), Timestamp.class);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
    }
}
