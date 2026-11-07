package com.freightflow.logistics.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;

@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public IdempotencyCleanupJob(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${freightflow.logistics.idempotency.cleanup-interval:15m}")
    @Transactional
    public int purgeExpiredKeys() {
        int deleted = jdbcTemplate.update(
                """
                DELETE FROM idempotency_keys
                WHERE (scope, idempotency_key) IN (
                    SELECT scope, idempotency_key
                    FROM idempotency_keys
                    WHERE expires_at <= ?
                    ORDER BY expires_at ASC
                    LIMIT 500
                    FOR UPDATE SKIP LOCKED
                )
                """,
                Timestamp.from(clock.instant())
        );
        if (deleted > 0) {
            log.info("Purged {} expired idempotency keys", deleted);
        }
        return deleted;
    }
}
