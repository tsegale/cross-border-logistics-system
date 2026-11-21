package com.freightflow.notification.events;

import com.freightflow.notification.deadletter.DeadLetterStore;
import com.freightflow.notification.notify.EventNotifier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * notification-service's consumers (ADR 0026): logistics' shipment events and banking's hold events, as group
 * {@code notification-service}.
 *
 * <p>A failure does not block the partition. The record moves to a retry topic and comes back after a delay
 * (non-blocking retries, Spring Kafka's {@code @RetryableTopic}); after the last attempt, or at once for a poison
 * message, it goes to the dead-letter topic, where {@link #onDeadLetter} keeps it for an operator
 * ({@code /api/v1/dead-letters}). The price is order: a retried event comes back after later ones of the same key.
 * The envelope's {@code sequence} makes that safe ({@code ShipmentProjection}); every email is idempotent by event
 * id.
 */
@Component
public class EventListeners {

    private final EventNotifier notifier;
    private final DeadLetterStore deadLetters;

    public EventListeners(EventNotifier notifier, DeadLetterStore deadLetters) {
        this.notifier = notifier;
        this.deadLetters = deadLetters;
    }

    @RetryableTopic(
            attempts = "${freightflow.events.consumer.attempts:4}",
            backOff = @BackOff(delayString = "${freightflow.events.consumer.retry-delay:1000}",
                    multiplierString = "${freightflow.events.consumer.retry-multiplier:5}",
                    maxDelayString = "${freightflow.events.consumer.retry-max-delay:60000}"),
            retryTopicSuffix = EventTopics.RETRY_SUFFIX,
            dltTopicSuffix = EventTopics.DLT_SUFFIX,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            numPartitions = "3",
            replicationFactor = "${freightflow.kafka.replication-factor}",
            exclude = PoisonEventException.class,
            traversingCauses = "true")
    @KafkaListener(id = "notification-shipment-events", topics = EventTopics.SHIPMENT, groupId = EventTopics.GROUP,
            autoStartup = "${freightflow.events.consumer.enabled:true}")
    public void onShipmentEvent(ConsumerRecord<String, String> record) {
        notifier.onEvent(ConsumedEvent.parse(record.value()), EventTopics.SHIPMENT);
    }

    @RetryableTopic(
            attempts = "${freightflow.events.consumer.attempts:4}",
            backOff = @BackOff(delayString = "${freightflow.events.consumer.retry-delay:1000}",
                    multiplierString = "${freightflow.events.consumer.retry-multiplier:5}",
                    maxDelayString = "${freightflow.events.consumer.retry-max-delay:60000}"),
            retryTopicSuffix = EventTopics.RETRY_SUFFIX,
            dltTopicSuffix = EventTopics.DLT_SUFFIX,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            numPartitions = "3",
            replicationFactor = "${freightflow.kafka.replication-factor}",
            exclude = PoisonEventException.class,
            traversingCauses = "true")
    @KafkaListener(id = "notification-hold-events", topics = EventTopics.HOLD, groupId = EventTopics.GROUP,
            autoStartup = "${freightflow.events.consumer.enabled:true}")
    public void onHoldEvent(ConsumerRecord<String, String> record) {
        notifier.onEvent(ConsumedEvent.parse(record.value()), EventTopics.HOLD);
    }

    /**
     * Both listeners' dead letters: stored for listing and redrive, never lost. The source topic is the dead-letter
     * topic's name without its suffix, whichever retry topic the record came through last.
     */
    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        // Retry topics forward the failure as kafka_exception-*; a plain dead-letter recoverer as kafka_dlt-*.
        String error = header(record, KafkaHeaders.EXCEPTION_MESSAGE, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        String type = header(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        if (error != null && type != null) {
            error = type + ": " + error;
        }
        String source = record.topic().endsWith(EventTopics.DLT_SUFFIX)
                ? record.topic().substring(0, record.topic().length() - EventTopics.DLT_SUFFIX.length())
                : record.topic();
        deadLetters.save(record, source, error);
    }

    /** The last value of the first of these headers the record has, as text; null if none. */
    private static String header(ConsumerRecord<String, String> record, String... names) {
        for (String name : names) {
            org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
            if (header != null) {
                return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
