package com.freightflow.logistics.events;

import com.freightflow.logistics.outbox.OutboxClaims;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.KafkaBroker;
import com.freightflow.logistics.support.LogisticsFixtures;
import com.freightflow.logistics.support.Relays;
import com.freightflow.logistics.support.SharedContainers;
import com.freightflow.logistics.telemetry.TraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MockMvc;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * logistics' copy of the relay against a real broker (ADR 0025), on the claim mechanism it shares with the payment
 * commands ({@link OutboxClaims}): what is acknowledged is marked, nothing is lost while the broker is
 * down, a relay that dies after sending makes a duplicate and never a gap, and relays racing on several instances keep
 * each aggregate in order.
 *
 * <p>The Spring context's relay is off in tests; each test builds its own relays on the outbox table, the way
 * several service instances would share it. Tests that stop the broker use one of their own, not the shared one.
 */
class EventRelayIntegrationTest extends IntegrationTest {

    /** Its own broker, paused and resumed by the broker-down tests. */
    private static final KafkaBroker FLAKY = new KafkaBroker();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxClaims claims;

    @Autowired
    private TraceContext traces;

    @Autowired
    private EventOutbox outbox;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Clock clock;

    private LogisticsFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new LogisticsFixtures(mockMvc);
    }

    @AfterAll
    static void stopFlakyBroker() {
        FLAKY.stop();
    }

    @Test
    void anAcknowledgedEventIsMarkedAndCarriesItsKeyAndHeaders() {
        UUID shipment = shipmentWithEvents(1);
        EventRelay relay = relay(SharedContainers.KAFKA, outbox);

        drain(relay, shipment);

        List<ConsumerRecord<String, String>> records = consume(SharedContainers.KAFKA, EventTopics.SHIPMENT, Set.of(shipment), 2);
        assertThat(records).hasSize(2);
        ConsumerRecord<String, String> first = records.getFirst();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, envelope::text AS envelope, published_at FROM event_outbox WHERE aggregate_id = ? AND sequence = 1",
                shipment);
        assertThat(first.key()).isEqualTo(shipment.toString());
        assertThat(first.value()).isEqualTo(row.get("envelope"));
        assertThat(header(first, EventHeaders.EVENT_ID)).isEqualTo(row.get("id").toString());
        assertThat(header(first, EventHeaders.EVENT_TYPE)).isEqualTo("shipment.booked");
        assertThat(header(first, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
        assertThat(header(first, EventHeaders.AGGREGATE_ID)).isEqualTo(shipment.toString());
        assertThat(header(first, EventHeaders.SEQUENCE)).isEqualTo("1");
        assertThat(row.get("published_at")).isNotNull();
    }

    @Test
    void eventsWaitWhileTheBrokerIsDownAndAllArriveInOrderOnceItIsBack() {
        FLAKY.start();
        EventRelay relay = relay(FLAKY, outbox);
        UUID before = shipmentWithEvents(1);
        drain(relay, before);

        pause(FLAKY);
        try {
            UUID during = shipmentWithEvents(4);
            assertThat(relay.relayOnce()).as("nothing can be acknowledged by a paused broker").isZero();
            assertThat(unpublished(during)).isEqualTo(5);
            assertThat(jdbcTemplate.queryForObject("SELECT last_error FROM event_outbox WHERE aggregate_id = ? "
                    + "AND sequence = 1", String.class, during)).isNotBlank();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM event_outbox WHERE aggregate_id = ? "
                    + "AND attempts > 0", Integer.class, during))
                    .as("only the aggregate's oldest event was tried; the rest wait behind it").isEqualTo(1);

            unpause(FLAKY);
            makeDue(during);
            drain(relay, during);

            // A batch the paused broker had buffered may be written after the client gave up on it, and is then
            // sent again: at least once, in order, no gaps. Consumers drop the copy by eventId.
            List<ConsumerRecord<String, String>> records = consume(FLAKY, EventTopics.SHIPMENT, Set.of(during), 5);
            assertInOrderWithoutGaps(records, 5);
        } finally {
            unpause(FLAKY);
        }
    }

    @Test
    void topicsThatCouldNotBeDeclaredAtStartupAreDeclaredBeforeTheFirstSend() {
        FLAKY.start();
        pause(FLAKY);
        EventRelay relay = relay(FLAKY, outbox);
        UUID shipment = shipmentWithEvents(0);
        try {
            assertThat(relay.relayOnce()).as("topics could not be declared: nothing is claimed").isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM event_outbox WHERE aggregate_id = ?",
                    Integer.class, shipment)).isZero();
        } finally {
            unpause(FLAKY);
        }
        drain(relay, shipment);
        assertThat(consume(FLAKY, EventTopics.SHIPMENT, Set.of(shipment), 1)).hasSize(1);
    }

    @Test
    void aRelayThatDiesBeforeMarkingSendsTheEventAgainAndNeverSkipsOne() {
        UUID shipment = shipmentWithEvents(1);
        EventOutbox dying = new EventOutbox(jdbcTemplate, claims, clock, traces) {
            @Override
            public void markPublished(List<UUID> eventIds) {
                throw new IllegalStateException("relay died after the broker acknowledged");
            }
        };
        // Other classes' leftover events can fill the first batches: the relay dies once per batch until it has sent
        // this shipment's event too (its claim, committed before the send, changes the row).
        EventRelay dyingRelay = relay(SharedContainers.KAFKA, dying);
        List<RuntimeException> deaths = new ArrayList<>();
        Relays.events(() -> {
            try {
                return dyingRelay.relayOnce();
            } catch (IllegalStateException died) {
                deaths.add(died);
                return 1;
            }
        }, jdbcTemplate, clock, shipment);
        assertThat(deaths).as("the relay died after sending").isNotEmpty()
                .allSatisfy(died -> assertThat(died).hasMessageContaining("relay died"));
        assertThat(unpublished(shipment)).as("the row was never marked").isEqualTo(2);

        // The dead relay's lease runs out; another instance picks the event up again.
        makeDue(shipment);
        drain(relay(SharedContainers.KAFKA, outbox), shipment);

        List<ConsumerRecord<String, String>> records = consume(SharedContainers.KAFKA, EventTopics.SHIPMENT, Set.of(shipment), 3);
        assertThat(records).extracting(record -> header(record, EventHeaders.SEQUENCE))
                .as("sequence 1 twice, the same event both times, then 2").containsExactly("1", "1", "2");
        assertThat(header(records.get(0), EventHeaders.EVENT_ID)).isEqualTo(header(records.get(1), EventHeaders.EVENT_ID));
    }

    @Test
    void relaysRacingOnSeveralInstancesKeepEveryAggregateInOrder() throws Exception {
        List<UUID> aggregates = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            aggregates.add(shipmentWithEvents(15));
        }
        OutboxProperties small = properties(3);
        List<EventRelay> relays = List.of(relay(SharedContainers.KAFKA, outbox, small),
                relay(SharedContainers.KAFKA, outbox, small), relay(SharedContainers.KAFKA, outbox, small),
                relay(SharedContainers.KAFKA, outbox, small));
        ExecutorService pool = Executors.newFixedThreadPool(relays.size());
        try {
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            while (aggregates.stream().anyMatch(id -> unpublished(id) > 0) && System.nanoTime() < deadline) {
                List<Future<Integer>> runs = new ArrayList<>();
                for (EventRelay relay : relays) {
                    runs.add(pool.submit(relay::relayOnce));
                }
                for (Future<Integer> run : runs) {
                    run.get();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(aggregates).allSatisfy(id -> assertThat(unpublished(id)).isZero());

        List<ConsumerRecord<String, String>> records = consume(SharedContainers.KAFKA, EventTopics.SHIPMENT,
                new HashSet<>(aggregates), aggregates.size() * 16);
        Map<String, List<Long>> sequences = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            sequences.computeIfAbsent(record.key(), key -> new ArrayList<>())
                    .add(Long.parseLong(header(record, EventHeaders.SEQUENCE)));
        }
        assertThat(sequences).hasSize(aggregates.size());
        List<Long> expected = java.util.stream.LongStream.rangeClosed(1, 16).boxed().toList();
        sequences.forEach((aggregate, seen) -> assertThat(seen).as(aggregate).containsExactlyElementsOf(expected));
    }

    @Test
    void publishedEventsArePrunedAfterTheRetentionAndUnpublishedOnesNever() {
        UUID published = shipmentWithEvents(0);
        drain(relay(SharedContainers.KAFKA, outbox), published);
        UUID waiting = shipmentWithEvents(0);
        jdbcTemplate.update("UPDATE event_outbox SET published_at = now() - interval '8 days' WHERE aggregate_id = ?",
                published);
        jdbcTemplate.update("UPDATE event_outbox SET occurred_at = now() - interval '30 days' WHERE aggregate_id = ?",
                waiting);

        new OutboxPruner(outbox, properties(200), clock).prune();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM event_outbox WHERE aggregate_id = ?",
                Integer.class, published)).isZero();
        assertThat(unpublished(waiting)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT last_sequence FROM event_sequences WHERE aggregate_id = ?",
                Long.class, published)).as("the sequence survives its events").isEqualTo(1L);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A shipment awaiting payment with {@code trackingEvents} tracking events: 1 + trackingEvents events, in order.
     * The carrier events are rejected (a shipment awaiting payment does not move), so each writes exactly one event.
     */
    private UUID shipmentWithEvents(int trackingEvents) {
        try {
            UUID shipment = UUID.fromString(fixtures.bookShipment().get("id").asString());
            Instant at = Instant.now().minus(1, ChronoUnit.HOURS);
            for (int i = 0; i < trackingEvents; i++) {
                fixtures.recordEvent(shipment.toString(), "relay-" + shipment + "-" + i, "IN_TRANSIT",
                        at.plusSeconds(i).toString());
            }
            return shipment;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OutboxProperties properties(int batchSize) {
        return new OutboxProperties(false, Duration.ofMillis(200), batchSize, 20, Duration.ofSeconds(20),
                Duration.ofSeconds(8), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofDays(7), 1000);
    }

    private EventRelay relay(KafkaBroker broker, EventOutbox store) {
        return relay(broker, store, properties(200));
    }

    private EventRelay relay(KafkaBroker broker, EventOutbox store, OutboxProperties properties) {
        Map<String, Object> config = new HashMap<>(broker.clientConfig("logistics"));
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer()));
        KafkaAdmin admin = new KafkaAdmin(broker.clientConfig("logistics"));
        admin.setOperationTimeout(3);
        // The topics are the context's NewTopic beans (EventTopics), as in the running service.
        admin.setApplicationContext(context);
        return new EventRelay(store, claims, template, admin, properties, clock, new SimpleMeterRegistry(), traces);
    }

    /** Runs the relay until every event of the aggregate is acknowledged (leftovers of other tests go along). */
    private void drain(EventRelay relay, UUID aggregate) {
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (unpublished(aggregate) > 0) {
            assertThat(System.nanoTime()).as("events of %s still unpublished", aggregate).isLessThan(deadline);
            relay.relayOnce();
        }
    }

    private int unpublished(UUID aggregate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE aggregate_id = ? AND published_at IS NULL", Integer.class, aggregate);
        return count == null ? 0 : count;
    }

    /** As if every lease and backoff had run out. */
    private void makeDue(UUID aggregate) {
        // A minute back: due by the service's clock too, which can be behind the database's on Docker Desktop.
        jdbcTemplate.update("UPDATE event_outbox SET next_attempt_at = now() - interval '1 minute' "
                + "WHERE aggregate_id = ? AND published_at IS NULL", aggregate);
    }

    /** Reads the topic from the start as admin (logistics may only write it) until {@code count} records match. */
    private static List<ConsumerRecord<String, String>> consume(KafkaBroker broker, String topic, Set<UUID> keys,
                                                                int count) {
        Set<String> wanted = new HashSet<>();
        keys.forEach(key -> wanted.add(key.toString()));
        Map<String, Object> config = new HashMap<>(broker.clientConfig("admin"));
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, String>> matching = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (matching.size() < count && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (wanted.contains(record.key())) {
                        matching.add(record);
                    }
                });
            }
            // Anything more would be an unexpected duplicate: read a little longer to see it.
            consumer.poll(Duration.ofSeconds(1)).forEach(record -> {
                if (wanted.contains(record.key())) {
                    matching.add(record);
                }
            });
        }
        return matching;
    }

    /** Sequences 1..n by first appearance, and every copy of one sequence is the same event. */
    private static void assertInOrderWithoutGaps(List<ConsumerRecord<String, String>> records, int n) {
        Map<String, String> eventIdBySequence = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            String eventId = eventIdBySequence.putIfAbsent(header(record, EventHeaders.SEQUENCE),
                    header(record, EventHeaders.EVENT_ID));
            if (eventId != null) {
                assertThat(header(record, EventHeaders.EVENT_ID)).as("a copy is the same event").isEqualTo(eventId);
            }
        }
        assertThat(eventIdBySequence.keySet()).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, n).mapToObj(Integer::toString).toList());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static void pause(KafkaBroker broker) {
        broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
    }

    private static void unpause(KafkaBroker broker) {
        if (Boolean.TRUE.equals(broker.getDockerClient().inspectContainerCmd(broker.getContainerId()).exec()
                .getState().getPaused())) {
            broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();
        }
    }

    private static String key() {
        return "relay-" + UUID.randomUUID();
    }
}
