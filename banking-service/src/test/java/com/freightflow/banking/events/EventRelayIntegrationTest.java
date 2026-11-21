package com.freightflow.banking.events;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.hold.HoldService;
import com.freightflow.banking.hold.dto.CreateHoldRequest;
import com.freightflow.banking.hold.dto.ExtendHoldRequest;
import com.freightflow.banking.hold.dto.HoldResponse;
import com.freightflow.banking.support.Funding;
import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.support.KafkaBroker;
import com.freightflow.banking.support.SharedContainers;
import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.telemetry.TraceContext;
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

import java.math.BigDecimal;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay against a real broker (ADR 0025): what is acknowledged is marked, nothing is lost while the broker is
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
    private AccountService accounts;

    @Autowired
    private Funding funding;

    @Autowired
    private HoldService holds;

    @Autowired
    private EventOutbox outbox;

    @Autowired
    private TraceContext traces;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Clock clock;

    @AfterAll
    static void stopFlakyBroker() {
        FLAKY.stop();
    }

    @Test
    void anAcknowledgedEventIsMarkedAndCarriesItsKeyAndHeaders() {
        UUID hold = holdWithExtensions(1);
        EventRelay relay = relay(SharedContainers.KAFKA, outbox);

        drain(relay, hold);

        List<ConsumerRecord<String, String>> records = consume(SharedContainers.KAFKA, EventTopics.HOLD, Set.of(hold), 2);
        assertThat(records).hasSize(2);
        ConsumerRecord<String, String> first = records.getFirst();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, envelope::text AS envelope, published_at FROM event_outbox WHERE aggregate_id = ? AND sequence = 1",
                hold);
        assertThat(first.key()).isEqualTo(hold.toString());
        assertThat(first.value()).isEqualTo(row.get("envelope"));
        assertThat(header(first, EventHeaders.EVENT_ID)).isEqualTo(row.get("id").toString());
        assertThat(header(first, EventHeaders.EVENT_TYPE)).isEqualTo("hold.authorised");
        assertThat(header(first, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
        assertThat(header(first, EventHeaders.AGGREGATE_ID)).isEqualTo(hold.toString());
        assertThat(header(first, EventHeaders.SEQUENCE)).isEqualTo("1");
        assertThat(row.get("published_at")).isNotNull();
    }

    @Test
    void eventsWaitWhileTheBrokerIsDownAndAllArriveInOrderOnceItIsBack() {
        FLAKY.start();
        EventRelay relay = relay(FLAKY, outbox);
        UUID before = holdWithExtensions(1);
        drain(relay, before);

        pause(FLAKY);
        try {
            UUID during = holdWithExtensions(4);
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
            List<ConsumerRecord<String, String>> records = consume(FLAKY, EventTopics.HOLD, Set.of(during), 5);
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
        UUID hold = holdWithExtensions(0);
        try {
            assertThat(relay.relayOnce()).as("topics could not be declared: nothing is claimed").isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM event_outbox WHERE aggregate_id = ?",
                    Integer.class, hold)).isZero();
        } finally {
            unpause(FLAKY);
        }
        drain(relay, hold);
        assertThat(consume(FLAKY, EventTopics.HOLD, Set.of(hold), 1)).hasSize(1);
    }

    @Test
    void aRelayThatDiesBeforeMarkingSendsTheEventAgainAndNeverSkipsOne() {
        UUID hold = holdWithExtensions(1);
        UUID first = jdbcTemplate.queryForObject("SELECT id FROM event_outbox WHERE aggregate_id = ? AND sequence = 1",
                UUID.class, hold);
        AtomicBoolean acknowledged = new AtomicBoolean();
        EventOutbox dying = new EventOutbox(jdbcTemplate, clock, traces) {
            @Override
            public void markPublished(List<UUID> eventIds) {
                // A send the broker did not acknowledge in time (a slow runner) is not the case under test: only an
                // acknowledged one is followed by the relay dying.
                if (eventIds.contains(first)) {
                    acknowledged.set(true);
                    throw new IllegalStateException("relay died after the broker acknowledged");
                }
                super.markPublished(eventIds);
            }
        };
        EventRelay dyingRelay = relay(SharedContainers.KAFKA, dying);
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (!acknowledged.get()) {
            assertThat(System.nanoTime()).as("the broker never acknowledged the first event").isLessThan(deadline);
            makeDue(hold);
            try {
                dyingRelay.relayOnce();
            } catch (IllegalStateException died) {
                assertThat(died).hasMessageContaining("relay died");
            }
        }
        assertThat(unpublished(hold)).as("the row was never marked").isEqualTo(2);

        // The dead relay's lease runs out; another instance picks the event up again.
        makeDue(hold);
        drain(relay(SharedContainers.KAFKA, outbox), hold);

        List<ConsumerRecord<String, String>> records = consume(SharedContainers.KAFKA, EventTopics.HOLD, Set.of(hold), 3);
        assertThat(records).extracting(record -> header(record, EventHeaders.SEQUENCE))
                .as("sequence 1 twice, the same event both times, then 2").containsExactly("1", "1", "2");
        assertThat(header(records.get(0), EventHeaders.EVENT_ID)).isEqualTo(header(records.get(1), EventHeaders.EVENT_ID));
    }

    @Test
    void relaysRacingOnSeveralInstancesKeepEveryAggregateInOrder() throws Exception {
        List<UUID> aggregates = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            aggregates.add(holdWithExtensions(15));
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

        List<ConsumerRecord<String, String>> records = consume(SharedContainers.KAFKA, EventTopics.HOLD,
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
        UUID published = holdWithExtensions(0);
        drain(relay(SharedContainers.KAFKA, outbox), published);
        UUID waiting = holdWithExtensions(0);
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

    /** A hold with its authorisation and {@code extensions} extensions: 1 + extensions events, in order. */
    private UUID holdWithExtensions(int extensions) {
        AccountResponse account = accounts.createCustomerAccount(TestCallers.CUSTOMER_NAMIB,
                new CreateAccountRequest("EUR", null));
        funding.deposit(account.accountNumber(), "100.00", "EUR");
        HoldResponse hold = holds.createHold(TestCallers.LOGISTICS, new CreateHoldRequest(account.accountNumber(),
                new BigDecimal("1.00"), "EUR", "SHIPMENT " + UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.DAYS),
                TestCallers.NAMIB, null), key()).body();
        Instant expiry = hold.expiresAt();
        for (int i = 0; i < extensions; i++) {
            expiry = expiry.plus(1, ChronoUnit.MINUTES);
            holds.extend(TestCallers.LOGISTICS, hold.id(), new ExtendHoldRequest(expiry), key());
        }
        return hold.id();
    }

    private OutboxProperties properties(int batchSize) {
        return new OutboxProperties(false, Duration.ofMillis(200), batchSize, 20, Duration.ofSeconds(20),
                Duration.ofSeconds(8), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofDays(7), 1000);
    }

    private EventRelay relay(KafkaBroker broker, EventOutbox store) {
        return relay(broker, store, properties(200));
    }

    private EventRelay relay(KafkaBroker broker, EventOutbox store, OutboxProperties properties) {
        Map<String, Object> config = new HashMap<>(broker.clientConfig("banking"));
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer()));
        KafkaAdmin admin = new KafkaAdmin(broker.clientConfig("banking"));
        admin.setOperationTimeout(3);
        // The topics are the context's NewTopic beans (EventTopics), as in the running service.
        admin.setApplicationContext(context);
        return new EventRelay(store, template, admin, properties, clock, new SimpleMeterRegistry(), traces);
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
        jdbcTemplate.update("UPDATE event_outbox SET next_attempt_at = now() WHERE aggregate_id = ? "
                + "AND published_at IS NULL", aggregate);
    }

    /** Reads the topic from the start as admin (banking may only write it) until {@code count} records match. */
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
