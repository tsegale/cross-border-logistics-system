package com.freightflow.logistics.support;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One relay run as far as one aggregate is concerned. Test classes share their database and run in random order, so
 * another class can leave more due rows than one relay batch claims, and the rows of the test that relays need not
 * be in the first batch. These run the relay until every row of the aggregate that was due has been claimed once
 * (a claim always moves {@code attempts} and {@code next_attempt_at}, so any change to the row shows it was reached),
 * or until the relay finds nothing more to claim. A row that fails stays pending, as after one run of its own.
 */
public final class Relays {

    private static final int MAX_ROUNDS = 100;

    private Relays() {
    }

    /** The payment relay, for the commands of these shipments ({@code PaymentCommandRelay::relayOnce}). */
    public static void payments(IntSupplier relayOnce, JdbcTemplate jdbc, Clock clock, Object... shipments) {
        untilClaimed(relayOnce, jdbc, clock, "shipment_payments", "status = 'PENDING'", "shipment_id", shipments);
    }

    /** The event relay, for the events of these aggregates ({@code EventRelay::relayOnce}). */
    public static void events(IntSupplier relayOnce, JdbcTemplate jdbc, Clock clock, Object... aggregates) {
        untilClaimed(relayOnce, jdbc, clock, "event_outbox", "published_at IS NULL", "aggregate_id", aggregates);
    }

    private static void untilClaimed(IntSupplier relayOnce, JdbcTemplate jdbc, Clock clock, String table,
                                     String pending, String aggregateColumn, Object... aggregates) {
        String[] ids = Arrays.stream(aggregates).map(a -> UUID.fromString(a.toString()).toString()).toArray(String[]::new);
        Map<String, String> due = rows(jdbc, table, pending, aggregateColumn, ids, Timestamp.from(clock.instant()));
        int claimed = relayOnce.getAsInt();
        for (int round = 1; round < MAX_ROUNDS && claimed > 0 && !allChanged(jdbc, table, ids, aggregateColumn, due);
             round++) {
            claimed = relayOnce.getAsInt();
        }
    }

    private static Map<String, String> rows(JdbcTemplate jdbc, String table, String pending, String aggregateColumn,
                                            String[] aggregates, Timestamp now) {
        return jdbc.query("SELECT id::text AS id, md5(t::text) AS fingerprint FROM " + table + " t WHERE " + pending
                        + " AND next_attempt_at <= ? AND " + aggregateColumn + " = ANY (?::uuid[])",
                        (rs, row) -> Map.entry(rs.getString("id"), rs.getString("fingerprint")), now, (Object) aggregates)
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static boolean allChanged(JdbcTemplate jdbc, String table, String[] aggregates, String aggregateColumn,
                                      Map<String, String> before) {
        if (before.isEmpty()) {
            return true;
        }
        List<Map.Entry<String, String>> now = jdbc.query(
                "SELECT id::text AS id, md5(t::text) AS fingerprint FROM " + table + " t WHERE " + aggregateColumn + " = ANY (?::uuid[])",
                (rs, row) -> Map.entry(rs.getString("id"), rs.getString("fingerprint")), (Object) aggregates);
        Map<String, String> current = now.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return before.entrySet().stream().noneMatch(row -> row.getValue().equals(current.get(row.getKey())));
    }
}
