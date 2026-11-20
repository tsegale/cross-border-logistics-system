package com.freightflow.logistics.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Consumed events by id (ADR 0026). Recording one joins the transaction that acts on it, so the effect and the record
 * commit together: a redelivery after a crash, a rebalance or a replay finds the record and does nothing.
 */
@Component
public class ProcessedEvents {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEvents.class);
    /** Longer than the topics' retention (30 days, ADR 0022): no older event can be delivered again. */
    static final Duration KEEP = Duration.ofDays(35);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ProcessedEvents(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Records the event; false if it was recorded before. A second consumer of the same event waits on the primary key
     * until the first commits, then finds it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(UUID eventId, String topic, String type) {
        return jdbcTemplate.update("""
                INSERT INTO processed_events (event_id, topic, event_type, processed_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, topic, type, Timestamp.from(clock.instant())) == 1;
    }

    @Scheduled(fixedDelayString = "${freightflow.events.consumer.prune-interval:6h}", initialDelayString = "5m")
    public int prune() {
        int deleted = jdbcTemplate.update("DELETE FROM processed_events WHERE processed_at < ?",
                Timestamp.from(clock.instant().minus(KEEP)));
        if (deleted > 0) {
            log.info("Pruned {} processed events older than {}", deleted, KEEP);
        }
        return deleted;
    }
}
