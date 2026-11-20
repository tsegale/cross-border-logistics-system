package com.freightflow.logistics.events;

import com.freightflow.logistics.payment.PaymentCommand;
import com.freightflow.logistics.payment.PaymentCommandRelay;
import com.freightflow.logistics.payment.PaymentCommandRepository;
import com.freightflow.logistics.payment.PaymentCommandStatus;
import com.freightflow.logistics.payment.PaymentCommandType;
import com.freightflow.logistics.payment.PaymentCommands;
import com.freightflow.logistics.shipment.PaymentStatus;
import com.freightflow.logistics.shipment.Shipment;
import com.freightflow.logistics.shipment.ShipmentRepository;
import com.freightflow.logistics.shipment.ShipmentStatus;
import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.EventSchemas;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import com.freightflow.logistics.support.SharedContainers;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * logistics reacts to banking's hold events (ADR 0028) through its real listener on the real broker: events are
 * published as banking publishes them, as the banking user, to {@code freightflow.hold.v1}.
 */
class HoldEventConsumerIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentCommandRelay relay;

    @Autowired
    private PaymentCommandRepository commands;

    @Autowired
    private ShipmentRepository shipments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meters;

    private LogisticsFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new LogisticsFixtures(mockMvc, relay);
    }

    @Test
    void anExpiredHoldOfAShipmentAwaitingPickupIsAuthorisedAgain() throws Exception {
        UUID shipment = UUID.fromString(fixtures.bookAndPay().get("id").asString());
        UUID expiredHold = shipments.findById(shipment).orElseThrow().getHoldId();

        publish(holdEvent("hold.expired", shipment, expiredHold, UUID.randomUUID()));
        PaymentCommand reauthorise = await(() -> reauthorisation(shipment));

        assertThat(reauthorise.getIdempotencyKey()).isEqualTo(PaymentCommands.reauthorisationKey(shipment, expiredHold));
        assertThat(reauthorise.getExpiresAt()).isAfter(Instant.now());
        assertThat(shipments.findById(shipment).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.AUTHORISATION_PENDING);

        String newHold = UUID.randomUUID().toString();
        BankingStub.SERVER.stubFor(post(urlPathEqualTo("/api/v1/holds"))
                .withHeader("Idempotency-Key", equalTo(reauthorise.getIdempotencyKey()))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody(BankingStubs.hold("ACTIVE", "null").replace(BankingStubs.HOLD_ID, newHold))));
        relay.relayOnce();

        Shipment paidAgain = shipments.findById(shipment).orElseThrow();
        assertThat(paidAgain.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
        assertThat(paidAgain.getPaymentStatus()).isEqualTo(PaymentStatus.AUTHORISED);
        assertThat(paidAgain.getHoldId()).hasToString(newHold);
        assertThat(commands.findById(reauthorise.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentCommandStatus.SUCCEEDED);
    }

    @Test
    void aRedeliveredExpiryChangesNothingTheSecondTime() throws Exception {
        UUID shipment = UUID.fromString(fixtures.bookAndPay().get("id").asString());
        UUID hold = shipments.findById(shipment).orElseThrow().getHoldId();
        String expired = holdEvent("hold.expired", shipment, hold, UUID.randomUUID());

        publish(expired);
        publish(expired);
        // Same key, same partition: once this one is processed, both copies before it were.
        UUID marker = UUID.randomUUID();
        publish(holdEvent("hold.authorised", shipment, hold, marker), hold);
        await(() -> processed(marker) ? true : null);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM shipment_payments WHERE shipment_id = ? "
                + "AND command = 'REAUTHORISE'", Integer.class, shipment)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, UUID.fromString(JSON.readTree(expired).path("eventId").asString()))).isEqualTo(1);
    }

    @Test
    void anExpiryAfterPickupOrOfAnotherHoldIsLeftAlone() throws Exception {
        UUID pickedUp = UUID.fromString(fixtures.bookAndPay().get("id").asString());
        UUID hold = shipments.findById(pickedUp).orElseThrow().getHoldId();
        fixtures.recordEvent(pickedUp.toString(), "pickup-" + pickedUp, "PICKED_UP",
                Instant.now().minusSeconds(60).toString());
        UUID booked = UUID.fromString(fixtures.bookAndPay().get("id").asString());

        UUID afterPickup = UUID.randomUUID();
        UUID otherHold = UUID.randomUUID();
        publish(holdEvent("hold.expired", pickedUp, hold, afterPickup));
        publish(holdEvent("hold.expired", booked, UUID.randomUUID(), otherHold));
        await(() -> processed(afterPickup) && processed(otherHold) ? true : null);

        assertThat(reauthorisation(pickedUp)).isNull();
        assertThat(reauthorisation(booked)).isNull();
        assertThat(shipments.findById(booked).orElseThrow().getPaymentStatus()).isEqualTo(PaymentStatus.AUTHORISED);
    }

    @Test
    void aCaptureLogisticsNeverSentRaisesTheAlertMetricAndOneItSentDoesNot() throws Exception {
        UUID notPickedUp = UUID.fromString(fixtures.bookAndPay().get("id").asString());
        UUID pickedUp = UUID.fromString(fixtures.bookAndPay().get("id").asString());
        fixtures.recordEvent(pickedUp.toString(), "pickup-" + pickedUp, "PICKED_UP",
                Instant.now().minusSeconds(60).toString());
        UUID hold = shipments.findById(pickedUp).orElseThrow().getHoldId();
        double before = unexpectedCaptures();

        UUID expected = UUID.randomUUID();
        UUID unexpected = UUID.randomUUID();
        publish(holdEvent("hold.captured", pickedUp, hold, expected));
        publish(holdEvent("hold.captured", notPickedUp, hold, unexpected));
        await(() -> processed(expected) && processed(unexpected) ? true : null);

        assertThat(unexpectedCaptures() - before).isEqualTo(1.0);
    }

    @Test
    void aPoisonMessageGoesStraightToTheDeadLetterTopic() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        publish("{this is not an event", key);

        ConsumerRecord<String, String> dead = await(() -> deadLetter(key));
        assertThat(dead.value()).isEqualTo("{this is not an event");
        // Straight there: the exception is recorded on the record, no retry ran first.
        assertThat(new String(dead.headers().lastHeader("kafka_dlt-exception-fqcn").value())
                + new String(dead.headers().lastHeader("kafka_dlt-exception-message").value()))
                .contains("Not a readable event");
    }

    // ------------------------------------------------------------------ helpers

    /** The schema's example, made about this shipment and hold; it still matches the schema. */
    private static String holdEvent(String type, UUID shipment, UUID hold, UUID eventId) {
        ObjectNode event = (ObjectNode) EventSchemas.files().get(type + ".schema.json").path("examples").get(0)
                .deepCopy();
        event.put("eventId", uuidV7Like(eventId));
        event.put("aggregateId", hold.toString());
        ObjectNode payloadHold = (ObjectNode) event.path("payload").path("hold");
        payloadHold.put("holdId", hold.toString());
        payloadHold.put("reference", "SHIPMENT " + shipment);
        String json = event.toString();
        assertThat(EventSchemas.validate(type, json)).as("test event matches %s", type).isEmpty();
        return json;
    }

    /** The envelope wants a version-7 id; keep the random bits, set the version and variant. */
    private static String uuidV7Like(UUID random) {
        long msb = (random.getMostSignificantBits() & ~0xF000L) | 0x7000L;
        long lsb = (random.getLeastSignificantBits() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    private void publish(String event) {
        publish(event, JSON.readTree(event).path("aggregateId").asString());
    }

    private void publish(String event, UUID key) {
        publish(event, key.toString());
    }

    private static void publish(String value, String key) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(SharedContainers.KAFKA.clientConfig("banking"),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(SharedContainers.BANKING_HOLD_TOPIC, key, value)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish to " + SharedContainers.BANKING_HOLD_TOPIC, e);
        }
    }

    private boolean processed(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, UUID.fromString(uuidV7Like(eventId)));
        return count != null && count == 1;
    }

    private PaymentCommand reauthorisation(UUID shipment) {
        return commands.findByShipmentIdOrderByCreatedAtAsc(shipment).stream()
                .filter(command -> command.getCommand() == PaymentCommandType.REAUTHORISE).findFirst().orElse(null);
    }

    private double unexpectedCaptures() {
        return meters.counter("freightflow.payment.unexpected.captures").count();
    }

    private static ConsumerRecord<String, String> deadLetter(String key) {
        java.util.Map<String, Object> config = new java.util.HashMap<>(SharedContainers.KAFKA.clientConfig("admin"));
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new StringDeserializer())) {
            List<TopicPartition> partitions = new ArrayList<>();
            consumer.partitionsFor(EventTopics.HOLD_DEAD_LETTERS)
                    .forEach(info -> partitions.add(new TopicPartition(info.topic(), info.partition())));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(2))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    /** Polls until the supplier returns non-null, for up to 30 s (the listener is asynchronous). */
    private static <T> T await(java.util.function.Supplier<T> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            T value = condition.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("condition not met within 30 s");
    }
}
