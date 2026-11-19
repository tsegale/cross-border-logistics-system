package com.freightflow.logistics.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * The topic logistics owns and publishes to (ADR 0022). Spring Boot's {@code KafkaAdmin} creates it at startup and
 * brings its configuration in line; the broker never creates a topic on its own, and the ACLs let only logistics
 * create or write it.
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

    static final Duration RETENTION = Duration.ofDays(30);

    private final int replicationFactor;

    public EventTopics(@Value("${freightflow.kafka.replication-factor}") int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    @Bean
    NewTopic shipmentTopic() {
        return TopicBuilder.name(SHIPMENT)
                .partitions(6)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(RETENTION.toMillis()))
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(replicationFactor >= 3 ? 2 : 1))
                .build();
    }
}
