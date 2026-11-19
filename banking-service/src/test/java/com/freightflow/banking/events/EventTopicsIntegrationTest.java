package com.freightflow.banking.events;

import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.support.SharedContainers;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * banking declares its own topics at startup with the chosen partitions, retention and cleanup (ADR 0022), and, as
 * its own SCRAM user, writes them but not logistics' topic.
 */
class EventTopicsIntegrationTest extends IntegrationTest {

    private static final Map<String, Integer> PARTITIONS = Map.of(
            EventTopics.ACCOUNT, 3, EventTopics.JOURNAL_ENTRY, 6, EventTopics.HOLD, 6, EventTopics.APPROVAL, 3);

    @Test
    void bankingDeclaredEveryTopicItOwns() throws Exception {
        try (Admin admin = Admin.create(SharedContainers.KAFKA.clientConfig("banking"))) {
            Map<String, TopicDescription> topics = admin.describeTopics(PARTITIONS.keySet()).allTopicNames()
                    .get(30, TimeUnit.SECONDS);
            PARTITIONS.forEach((topic, partitions) -> assertThat(topics.get(topic).partitions()).as(topic)
                    .hasSize(partitions));
            for (String topic : PARTITIONS.keySet()) {
                Config config = admin.describeConfigs(List.of(new ConfigResource(ConfigResource.Type.TOPIC, topic)))
                        .all().get(30, TimeUnit.SECONDS).values().iterator().next();
                assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value()).as(topic)
                        .isEqualTo(Long.toString(Duration.ofDays(30).toMillis()));
                assertThat(config.get(TopicConfig.CLEANUP_POLICY_CONFIG).value()).as(topic).isEqualTo("delete");
            }
        }
    }

    @Test
    void bankingWritesItsOwnTopicsButNotLogistics() throws Exception {
        try (Admin admin = Admin.create(SharedContainers.KAFKA.clientConfig("admin"))) {
            admin.createTopics(List.of(new NewTopic("freightflow.shipment.v1", 1, (short) 1))).all()
                    .get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw e;
            }
        }
        Map<String, Object> config = new HashMap<>(SharedContainers.KAFKA.clientConfig("banking"));
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 15_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config, new StringSerializer(),
                new StringSerializer())) {
            assertThat(producer.send(new ProducerRecord<>(EventTopics.HOLD, "some-hold", "{}"))
                    .get(30, TimeUnit.SECONDS).topic()).isEqualTo(EventTopics.HOLD);
            assertThatThrownBy(() -> producer.send(new ProducerRecord<>("freightflow.shipment.v1", "some-shipment",
                    "{}")).get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TopicAuthorizationException.class);
        }
    }
}
