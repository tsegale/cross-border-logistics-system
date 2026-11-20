package com.freightflow.logistics.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Deletes published events after {@code freightflow.events.outbox.retention} (ADR 0025). Unpublished events are never
 * pruned, however old. Safe on several instances: each deletes a batch of whatever is still there.
 */
@Component
public class OutboxPruner {

    private static final Logger log = LoggerFactory.getLogger(OutboxPruner.class);

    private final EventOutbox outbox;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxPruner(EventOutbox outbox, OutboxProperties properties, Clock clock) {
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${freightflow.events.outbox.prune-interval:1h}", initialDelayString = "1m")
    public int prune() {
        Instant before = clock.instant().minus(properties.retention());
        int total = 0;
        int deleted;
        do {
            deleted = outbox.prunePublishedBefore(before, properties.pruneBatch());
            total += deleted;
        } while (deleted == properties.pruneBatch());
        if (total > 0) {
            log.info("Pruned {} published events older than {}", total, before);
        }
        return total;
    }
}
