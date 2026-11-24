package com.freightflow.logistics.telemetry;

import com.freightflow.logistics.events.EventRelay;
import com.freightflow.logistics.events.EventTopics;
import com.freightflow.logistics.payment.PaymentCommandRelay;
import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import com.freightflow.logistics.support.SharedContainers;
import com.freightflow.logistics.support.TelemetryLeaks;
import com.freightflow.logistics.support.TestSpans;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One booking is one trace (ADR 0030): the trace the gateway starts goes through the booking's transaction into both
 * outboxes, out of the payment relay to banking over HTTP, and out of the event relay to Kafka in the record's
 * traceparent header. And none of it carries the booking's contact addresses or a token (ADR 0024).
 */
@ExtendWith(OutputCaptureExtension.class)
class BookingTraceIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentCommandRelay payments;

    @Autowired
    private EventRelay events;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meters;

    @Test
    void aBookingsTraceCrossesBothOutboxesBankingAndKafkaWithoutPersonalData(CapturedOutput output) throws Exception {
        String traceId = hex(16);
        String traceparent = "00-" + traceId + "-" + hex(8) + "-01";
        LogisticsFixtures fixtures = new LogisticsFixtures(mockMvc);
        double bookedBefore = meters.counter("freightflow.shipments.booked").count();

        // As the gateway forwards it.
        MockHttpServletRequestBuilder booking = (MockHttpServletRequestBuilder) LogisticsFixtures.bookShipmentRequest(
                fixtures.createQuote(), "trace-" + UUID.randomUUID());
        MvcResult booked = mockMvc.perform(booking.header("traceparent", traceparent))
                .andExpect(status().isCreated())
                .andReturn();
        UUID shipment = UUID.fromString(LogisticsFixtures.json(booked).get("id").asString());

        assertThat(meters.counter("freightflow.shipments.booked").count()).isEqualTo(bookedBefore + 1);

        // The booking's transaction wrote its trace into both outboxes.
        assertThat(jdbcTemplate.queryForList("SELECT traceparent FROM event_outbox WHERE aggregate_id = ?",
                String.class, shipment)).isNotEmpty().allSatisfy(stored -> assertThat(stored).contains(traceId));
        assertThat(jdbcTemplate.queryForObject("SELECT traceparent FROM shipment_payments WHERE shipment_id = ? "
                + "AND command = 'AUTHORISE'", String.class, shipment)).contains(traceId);

        // The payment relay calls banking in it.
        BankingStub.SERVER.stubFor(BankingStubs.holdFor(shipment)
                .willReturn(BankingStubs.respond(BankingStubs.HOLD_CREATED)));
        // Test classes share this database, and another may have left commands due: one batch need not hold this
        // booking's, so the relay runs until it is sent or nothing is left to claim.
        List<LoggedRequest> holds = holdRequestsFor(shipment);
        for (int round = 0; holds.isEmpty() && round < 100 && payments.relayOnce() > 0; round++) {
            holds = holdRequestsFor(shipment);
        }
        assertThat(holds)
                .singleElement()
                .satisfies(request -> assertThat(request.getHeader("traceparent")).contains(traceId));

        // The event relay publishes in it, and the record carries it to every consumer.
        events.relayOnce();
        List<ConsumerRecord<String, String>> records = consume(shipment, 2);
        assertThat(records).isNotEmpty().allSatisfy(record -> {
            Header header = record.headers().lastHeader("traceparent");
            assertThat(header).as("traceparent header").isNotNull();
            assertThat(new String(header.value(), StandardCharsets.UTF_8)).contains(traceId);
        });

        List<SpanData> spans = TestSpans.trace(traceId, found -> TestSpans.names(found).containsAll(
                List.of("payment-command send", "event-outbox relay")));
        assertThat(TestSpans.names(spans)).contains("http post /api/v1/shipments", "payment-command send",
                "event-outbox relay");
        assertThat(spans).filteredOn(span -> span.getName().equals("payment-command send")).first()
                .satisfies(span -> assertThat(span.getAttributes().asMap().toString())
                        .contains("freightflow.shipment.id=" + shipment, "freightflow.payment_command.type=AUTHORISE"));
        assertThat(spans).filteredOn(span -> span.getName().equals("http post /api/v1/shipments")).first()
                .satisfies(span -> assertThat(span.getAttributes().asMap().toString())
                        .contains("freightflow.shipment.id=" + shipment));

        // The booking named two contact addresses: neither, nor a token, is in a span or a log line.
        assertThat(TelemetryLeaks.in(spans)).isEmpty();
        assertThat(TelemetryLeaks.in(output.getAll())).isEmpty();
    }

    private static List<ConsumerRecord<String, String>> consume(UUID key, int count) {
        Map<String, Object> config = new HashMap<>(SharedContainers.KAFKA.clientConfig("admin"));
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, String>> matching = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(EventTopics.SHIPMENT).stream()
                    .map(info -> new TopicPartition(EventTopics.SHIPMENT, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (matching.size() < count && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (key.toString().equals(record.key())) {
                        matching.add(record);
                    }
                });
            }
        }
        return matching;
    }

    private static String hex(int bytes) {
        byte[] random = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private static List<LoggedRequest> holdRequestsFor(UUID shipment) {
        return BankingStub.SERVER.findAll(postRequestedFor(urlPathEqualTo("/api/v1/holds"))).stream()
                .filter(request -> request.getHeader("Idempotency-Key").contains(shipment.toString()))
                .toList();
    }
}
