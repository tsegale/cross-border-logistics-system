package com.freightflow.notification.events;

import com.freightflow.notification.support.IntegrationTest;
import com.freightflow.notification.support.SharedContainers;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Least privilege on the broker for notification and for the collector's monitoring user (ADR 0022, ADR 0041),
 * against the broker compose runs: notification reads the shipment and hold events only as its own group, writes none
 * of them, and reads nothing else; monitoring describes groups and topics for consumer lag and reads or writes no
 * record.
 */
class KafkaAccessIntegrationTest extends IntegrationTest {

    private static final String SOME_KEY = "01a1f000-0000-7000-8000-000000000002";

    @Test
    void notificationReadsShipmentAndHoldEventsOnlyAsItsOwnGroup() {
        try (KafkaConsumer<String, String> consumer = consumer("notification", "notification-service")) {
            consumer.subscribe(List.of("freightflow.shipment.v1", "freightflow.hold.v1"));
            consumer.poll(Duration.ofSeconds(5));
            assertThat(consumer.subscription()).containsExactlyInAnyOrder("freightflow.shipment.v1",
                    "freightflow.hold.v1");
        }
        try (KafkaConsumer<String, String> consumer = consumer("notification", "logistics-service")) {
            consumer.subscribe(List.of("freightflow.hold.v1"));
            assertThatThrownBy(() -> consumer.poll(Duration.ofSeconds(10)))
                    .isInstanceOf(GroupAuthorizationException.class);
        }
    }

    @Test
    void notificationReadsNoneOfBankingsLedgerTopics() {
        for (String topic : List.of("freightflow.journal-entry.v1", "freightflow.account.v1")) {
            try (KafkaConsumer<String, String> consumer = consumer("notification", "notification-service")) {
                consumer.subscribe(List.of(topic));
                assertThatThrownBy(() -> consumer.poll(Duration.ofSeconds(10))).as(topic)
                        .isInstanceOf(TopicAuthorizationException.class);
            }
        }
    }

    @Test
    void notificationWritesNoOtherServicesTopic() {
        try (KafkaProducer<String, String> producer = producer("notification")) {
            for (String topic : List.of("freightflow.shipment.v1", "freightflow.hold.v1")) {
                assertThatThrownBy(() -> producer.send(new ProducerRecord<>(topic, SOME_KEY, "{}"))
                        .get(30, TimeUnit.SECONDS)).as(topic)
                        .hasCauseInstanceOf(TopicAuthorizationException.class);
            }
        }
    }

    @Test
    void monitoringDescribesGroupsButReadsAndWritesNoRecord() throws Exception {
        try (Admin admin = Admin.create(SharedContainers.KAFKA.clientConfig("monitoring"))) {
            assertThat(admin.listConsumerGroupOffsets("notification-service").partitionsToOffsetAndMetadata()
                    .get(30, TimeUnit.SECONDS)).isNotNull();
        }
        try (KafkaConsumer<String, String> consumer = consumer("monitoring", "monitoring-probe")) {
            consumer.subscribe(List.of("freightflow.shipment.v1"));
            assertThatThrownBy(() -> consumer.poll(Duration.ofSeconds(10)))
                    .isInstanceOfAny(GroupAuthorizationException.class, TopicAuthorizationException.class);
        }
        try (KafkaProducer<String, String> producer = producer("monitoring")) {
            // Refused before the topic is looked at: an idempotent producer needs IdempotentWrite on the cluster.
            assertThatThrownBy(() -> producer.send(new ProducerRecord<>("freightflow.shipment.v1", SOME_KEY, "{}"))
                    .get(30, TimeUnit.SECONDS))
                    .cause().isInstanceOfAny(org.apache.kafka.common.errors.ClusterAuthorizationException.class,
                            TopicAuthorizationException.class);
        }
    }

    private static KafkaProducer<String, String> producer(String user) {
        Map<String, Object> config = new HashMap<>(SharedContainers.KAFKA.clientConfig(user));
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 15_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        return new KafkaProducer<>(config, new StringSerializer(), new StringSerializer());
    }

    private static KafkaConsumer<String, String> consumer(String user, String group) {
        Map<String, Object> config = new HashMap<>(SharedContainers.KAFKA.clientConfig(user));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
    }
}
