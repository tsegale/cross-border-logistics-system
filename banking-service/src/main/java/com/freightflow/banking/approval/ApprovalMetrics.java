package com.freightflow.banking.approval;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/**
 * Four-eyes approvals waiting for a second operator (ADR 0021), as gauges read from the table, so every instance
 * reports the same numbers (ADR 0031): how many wait, how long the oldest has waited, and how many expire within the
 * hour unless someone decides.
 */
@Component
public class ApprovalMetrics {

    static final Duration NEAR_EXPIRY = Duration.ofHours(1);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ApprovalMetrics(JdbcTemplate jdbcTemplate, Clock clock, MeterRegistry meters) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        Gauge.builder("freightflow.approvals.pending", this, ApprovalMetrics::pending)
                .description("Approval requests waiting for a second operator")
                .register(meters);
        Gauge.builder("freightflow.approvals.pending.oldest.age", this, ApprovalMetrics::oldestAgeSeconds)
                .description("Seconds the oldest waiting approval request has waited; 0 when none waits")
                .baseUnit("seconds")
                .register(meters);
        Gauge.builder("freightflow.approvals.expiring", this, ApprovalMetrics::expiringSoon)
                .description("Waiting approval requests that expire within the hour")
                .register(meters);
    }

    double pending() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE status = 'PENDING'", Long.class);
        return count == null ? 0 : count;
    }

    double oldestAgeSeconds() {
        Timestamp oldest = jdbcTemplate.queryForObject(
                "SELECT min(requested_at) FROM approval_requests WHERE status = 'PENDING'", Timestamp.class);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
    }

    double expiringSoon() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE status = 'PENDING' AND expires_at < ?", Long.class,
                Timestamp.from(clock.instant().plus(NEAR_EXPIRY)));
        return count == null ? 0 : count;
    }
}
