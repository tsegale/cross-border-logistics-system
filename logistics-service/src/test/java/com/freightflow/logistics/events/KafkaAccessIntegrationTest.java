package com.freightflow.logistics.events;

import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.SharedContainers;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Least privilege on the broker (ADR 0022), against the broker compose runs: logistics logs in as its own SCRAM user,
 * writes only its own topic, reads banking's hold events only as its own consumer group, and cannot write, create or
 * read anything else. The context starts first, so the topic is the one logistics' {@code KafkaAdmin} declared.
 */
class KafkaAccessIntegrationTest extends IntegrationTest {

    private static final String SOME_KEY = "01a1f000-0000-7000-8000-000000000001";

    @BeforeAll
    static void bankingTopicsExist() throws Exception {
        // banking declares its own topics when it starts; here, the broker admin stands in for it.
        try (Admin admin = Admin.create(SharedContainers.KAFKA.clientConfig("admin"))) {
            for (String topic : List.of("freightflow.hold.v1", "freightflow.journal-entry.v1")) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
        }
    }

    @Test
    void logisticsDeclaredItsTopicWithTheChosenPartitionsRetentionAndCleanup() throws Exception {
        try (Admin admin = Admin.create(SharedContainers.KAFKA.clientConfig("logistics"))) {
            TopicDescription topic = admin.describeTopics(List.of(EventTopics.SHIPMENT)).allTopicNames()
                    .get(30, TimeUnit.SECONDS).get(EventTopics.SHIPMENT);
            assertThat(topic.partitions()).hasSize(6);
            Config config = admin.describeConfigs(List.of(new ConfigResource(ConfigResource.Type.TOPIC,
                    EventTopics.SHIPMENT))).all().get(30, TimeUnit.SECONDS).values().iterator().next();
            assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value())
                    .isEqualTo(Long.toString(Duration.ofDays(30).toMillis()));
            assertThat(config.get(TopicConfig.CLEANUP_POLICY_CONFIG).value()).isEqualTo("delete");
        }
    }

    @Test
    void logisticsWritesItsOwnTopicButCannotWriteOrCreateBankings() throws Exception {
        try (KafkaProducer<String, String> producer = producer("logistics", SharedContainers.KAFKA.password("logistics"))) {
            assertThat(producer.send(new ProducerRecord<>(EventTopics.SHIPMENT, SOME_KEY, "{}"))
                    .get(30, TimeUnit.SECONDS).topic()).isEqualTo(EventTopics.SHIPMENT);

            assertThatThrownBy(() -> producer.send(new ProducerRecord<>("freightflow.hold.v1", SOME_KEY, "{}"))
                    .get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TopicAuthorizationException.class);
        }
        try (Admin admin = Admin.create(SharedContainers.KAFKA.clientConfig("logistics"))) {
            assertThatThrownBy(() -> admin.createTopics(List.of(new NewTopic("freightflow.account.v1", 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TopicAuthorizationException.class);
        }
    }

    @Test
    void logisticsReadsHoldEventsOnlyAsItsOwnGroupAndNothingElse() {
        try (KafkaConsumer<String, String> consumer = consumer("logistics-service")) {
            consumer.subscribe(List.of("freightflow.hold.v1"));
            consumer.poll(Duration.ofSeconds(5));
            assertThat(consumer.subscription()).containsExactly("freightflow.hold.v1");
        }
        try (KafkaConsumer<String, String> consumer = consumer("logistics-service")) {
            consumer.subscribe(List.of("freightflow.journal-entry.v1"));
            assertThatThrownBy(() -> consumer.poll(Duration.ofSeconds(10)))
                    .isInstanceOf(TopicAuthorizationException.class);
        }
        try (KafkaConsumer<String, String> consumer = consumer("notification-service")) {
            consumer.subscribe(List.of("freightflow.hold.v1"));
            assertThatThrownBy(() -> consumer.poll(Duration.ofSeconds(10)))
                    .isInstanceOf(GroupAuthorizationException.class);
        }
    }

    @Test
    void aWrongPasswordIsRefusedBeforeAnything() {
        try (KafkaProducer<String, String> producer = producer("logistics", "not-the-password")) {
            // The send itself or its future fails, depending on when the client learns of the refusal.
            assertThatThrownBy(() -> producer.send(new ProducerRecord<>(EventTopics.SHIPMENT, SOME_KEY, "{}"))
                    .get(30, TimeUnit.SECONDS))
                    .satisfies(thrown -> assertThat(org.assertj.core.util.Throwables.getRootCause(thrown) == null
                            ? thrown : org.assertj.core.util.Throwables.getRootCause(thrown))
                            .isInstanceOf(SaslAuthenticationException.class));
        }
    }

    private static KafkaProducer<String, String> producer(String user, String password) {
        Map<String, Object> config = new java.util.HashMap<>(SharedContainers.KAFKA.clientConfig(user, password));
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 15_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        return new KafkaProducer<>(config, new StringSerializer(), new StringSerializer());
    }

    private static KafkaConsumer<String, String> consumer(String group) {
        Map<String, Object> config = new java.util.HashMap<>(SharedContainers.KAFKA.clientConfig("logistics"));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
    }
}
