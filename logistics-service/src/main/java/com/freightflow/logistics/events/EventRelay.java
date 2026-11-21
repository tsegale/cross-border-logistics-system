package com.freightflow.logistics.events;

import com.freightflow.logistics.outbox.Backoff;
import com.freightflow.logistics.outbox.OutboxClaims;
import com.freightflow.logistics.outbox.OutboxMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the event outbox to Kafka (ADR 0025).
 *
 * <ul>
 *   <li>The producer is idempotent with {@code acks=all} (application.yml), so a retry inside the client never
 *       writes an event twice and an acknowledged event is on every in-sync replica.</li>
 *   <li>A row is marked published only after its acknowledgement. A relay that dies between the two leaves the
 *       row unpublished; once its lease runs out another relay sends it again, and consumers drop the duplicate by
 *       {@code eventId}.</li>
 *   <li>Per-aggregate order holds across instances: a round sends at most one event per aggregate, the oldest
 *       unpublished one ({@link EventOutbox#claim}), and the next round starts after this one's acknowledgements.</li>
 *   <li>If the topics could not be declared at startup (broker down), they are declared before the first send.</li>
 * </ul>
 */
@Component
public class EventRelay {

    private static final Logger log = LoggerFactory.getLogger(EventRelay.class);

    private final EventOutbox outbox;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaAdmin admin;
    private final OutboxProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Timer lag;
    private volatile boolean topicsDeclared;

    public EventRelay(EventOutbox outbox, OutboxClaims claims, KafkaTemplate<String, String> kafka, KafkaAdmin admin,
                      OutboxProperties properties, Clock clock, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.admin = admin;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.lag = Timer.builder("freightflow.outbox.lag")
                .description("From an event's commit to the broker's acknowledgement")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meters);
        OutboxMetrics.register(meters, claims, "events", EventOutbox.TABLE, "occurred_at");
    }

    @Scheduled(fixedDelayString = "${freightflow.events.outbox.relay-interval:200ms}")
    public void scheduledRelay() {
        if (properties.relayEnabled()) {
            relayOnce();
        }
    }

    /**
     * Runs rounds until the outbox has nothing due, a send fails, or {@code maxRounds} is reached. Returns how many
     * events the broker acknowledged.
     */
    public int relayOnce() {
        if (!topicsDeclared && !declareTopics()) {
            return 0;
        }
        int published = 0;
        for (int round = 0; round < properties.maxRounds(); round++) {
            List<EventOutbox.PendingEvent> batch = outbox.claim(properties.batchSize(), properties.lease());
            if (batch.isEmpty()) {
                break;
            }
            RoundResult result = send(batch);
            outbox.markPublished(result.acknowledged());
            result.failed().forEach((event, error) -> retryLater(event, error));
            published += result.acknowledged().size();
            if (!result.failed().isEmpty()) {
                break;
            }
        }
        return published;
    }

    /** KafkaAdmin returns false or throws (a timeout) when the broker does not answer. */
    private boolean declareTopics() {
        try {
            topicsDeclared = admin.initialize();
        } catch (RuntimeException ex) {
            log.warn("Event topics could not be declared; events stay in the outbox until the broker answers: {}",
                    ex.toString());
            topicsDeclared = false;
        }
        return topicsDeclared;
    }

    private record RoundResult(List<UUID> acknowledged, Map<EventOutbox.PendingEvent, Throwable> failed) {
    }

    private RoundResult send(List<EventOutbox.PendingEvent> batch) {
        Map<EventOutbox.PendingEvent, CompletableFuture<SendResult<String, String>>> inFlight = new LinkedHashMap<>();
        Map<EventOutbox.PendingEvent, Throwable> failed = new LinkedHashMap<>();
        for (EventOutbox.PendingEvent event : batch) {
            try {
                inFlight.put(event, kafka.send(record(event)));
            } catch (RuntimeException ex) {
                // No metadata for the topic within max.block.ms, or the producer is closed.
                failed.put(event, ex);
            }
        }
        List<UUID> acknowledged = new ArrayList<>();
        long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
        for (Map.Entry<EventOutbox.PendingEvent, CompletableFuture<SendResult<String, String>>> entry
                : inFlight.entrySet()) {
            EventOutbox.PendingEvent event = entry.getKey();
            try {
                entry.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                acknowledged.add(event.eventId());
                lag.record(Duration.between(event.occurredAt(), clock.instant()));
                meters.counter("freightflow.outbox.published", "topic", event.topic(), "type", event.type())
                        .increment();
            } catch (ExecutionException ex) {
                failed.put(event, ex.getCause());
            } catch (java.util.concurrent.TimeoutException ex) {
                failed.put(event, ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failed.put(event, ex);
            }
        }
        return new RoundResult(acknowledged, failed);
    }

    private ProducerRecord<String, String> record(EventOutbox.PendingEvent event) {
        RecordHeaders headers = new RecordHeaders();
        header(headers, EventHeaders.EVENT_ID, event.eventId().toString());
        header(headers, EventHeaders.EVENT_TYPE, event.type());
        header(headers, EventHeaders.SCHEMA_VERSION, Integer.toString(LogisticsEvents.Type.of(event.type()).schemaVersion()));
        header(headers, EventHeaders.AGGREGATE_ID, event.aggregateId().toString());
        header(headers, EventHeaders.SEQUENCE, Long.toString(event.sequence()));
        return new ProducerRecord<>(event.topic(), null, event.aggregateId().toString(), event.envelope(), headers);
    }

    private static void header(RecordHeaders headers, String name, String value) {
        headers.add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
    }

    private void retryLater(EventOutbox.PendingEvent event, Throwable error) {
        if (missingTopic(error)) {
            // Deleted by hand, or never created: declare the topics again before the next send.
            topicsDeclared = false;
        }
        Instant retryAt = clock.instant().plus(backoff(event.attempts()));
        meters.counter("freightflow.outbox.send.failures", "topic", event.topic()).increment();
        log.warn("Event {} ({} #{} of {}) not acknowledged, attempt {}, retrying at {}: {}", event.eventId(),
                event.type(), event.sequence(), event.aggregateId(), event.attempts(), retryAt, error.toString());
        outbox.retryAt(event.eventId(), retryAt, error.toString());
    }

    private static boolean missingTopic(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownTopicOrPartitionException
                    || (cause instanceof TimeoutException && String.valueOf(cause.getMessage()).contains("metadata"))) {
                return true;
            }
        }
        return false;
    }

    Duration backoff(int attempt) {
        return new Backoff(properties.retryBackoff(), properties.maxRetryBackoff()).after(attempt);
    }
}
