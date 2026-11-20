package com.freightflow.logistics.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * The topics logistics owns and publishes to (ADR 0022): its shipment events and its dead letters. Spring Boot's {@code KafkaAdmin} creates them at startup and
 * brings their configuration in line; the broker never creates a topic on its own, and the ACLs let only logistics
 * create or write them.
 *
 * <p>{@code freightflow.shipment.v1} carries {@code shipment.booked}, {@code shipment.status-changed},
 * {@code shipment.payment-failed} and {@code tracking-event.recorded}, all keyed by the shipment id: a tracking event
 * belongs to its shipment's history, and one key keeps a shipment's events in order on one partition. Six
 * partitions (a shipment produces several events over its life), 30 days' retention and {@code delete} cleanup, for
 * the reasons banking's {@code EventTopics} gives; replication from {@code freightflow.kafka.replication-factor}.
 */
@Configuration
public class EventTopics {

    public static final String SHIPMENT = "freightflow.shipment.v1";

    /** banking's hold events, which logistics reads (ADR 0028); banking declares the topic. */
    public static final String BANKING_HOLD = "freightflow.hold.v1";
    /** logistics' consumer group, the one its ACLs allow (infra/kafka/acls.conf). */
    public static final String GROUP = "logistics-service";
    /** A consumed topic's dead letters go to {@code <topic>.logistics-service.dlt}, which logistics owns (ADR 0026). */
    public static final String DEAD_LETTER_SUFFIX = "." + GROUP + ".dlt";
    public static final String HOLD_DEAD_LETTERS = BANKING_HOLD + DEAD_LETTER_SUFFIX;

    static final Duration RETENTION = Duration.ofDays(30);

    private final int replicationFactor;

    public EventTopics(@Value("${freightflow.kafka.replication-factor}") int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    @Bean
    NewTopic shipmentTopic() {
        return topic(SHIPMENT, 6);
    }

    /** Dead letters are rare and read by hand: one partition, the same retention as the events. */
    @Bean
    NewTopic holdDeadLetterTopic() {
        return topic(HOLD_DEAD_LETTERS, 1);
    }

    private NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(RETENTION.toMillis()))
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(replicationFactor >= 3 ? 2 : 1))
                .build();
    }
}
