package com.freightflow.logistics.shipment;

import com.freightflow.logistics.payment.PaymentCommandRelay;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.freightflow.logistics.support.LogisticsFixtures.eventRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Carrier feeds miss scans, resend events and send them concurrently; none of that may lose an event (ADR 0009). */
class CarrierEventIngestionIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentCommandRelay relay;

    private LogisticsFixtures fixtures;
    private String shipmentId;

    @BeforeEach
    void bookedShipment() throws Exception {
        fixtures = new LogisticsFixtures(mockMvc, relay);
        shipmentId = fixtures.bookAndPay().get("id").asString();
    }

    @Test
    void eventThatSkipsMilestonesIsAppliedAndNamesTheSkippedOnes() throws Exception {
        JsonNode event = fixtures.recordEvent(shipmentId, "VESSEL-DEPARTED", "IN_TRANSIT", "2026-04-21T06:00:00Z");

        assertThat(event.get("appliedToStatus").asBoolean()).isTrue();
        assertThat(event.get("outcome").asString()).isEqualTo("APPLIED");
        assertThat(event.get("currentShipmentStatus").asString()).isEqualTo("IN_TRANSIT");
        assertThat(event.get("skippedStatuses").values().stream().map(JsonNode::asString).toList())
                .containsExactly("PICKED_UP", "AT_ORIGIN_PORT");
    }

    @Test
    void backwardMoveIsKeptInTheHistoryWithAReasonInsteadOfAConflict() throws Exception {
        fixtures.recordEvent(shipmentId, "ARRIVED", "AT_DESTINATION_PORT", "2026-05-08T10:00:00Z");

        // Later timestamp, earlier milestone: not out of order, but backwards.
        JsonNode event = fixtures.recordEvent(shipmentId, "BAD-SCAN", "AT_ORIGIN_PORT", "2026-05-08T11:00:00Z");

        assertThat(event.get("appliedToStatus").asBoolean()).isFalse();
        assertThat(event.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(event.get("outcomeReason").asString()).contains("AT_DESTINATION_PORT").contains("AT_ORIGIN_PORT");
        assertThat(event.get("currentShipmentStatus").asString()).isEqualTo("AT_DESTINATION_PORT");
        mockMvc.perform(get("/api/v1/shipments/track/{tn}", trackingNumber()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2));
    }

    @Test
    void eventOlderThanTheLatestAppliedOneIsOutOfOrder() throws Exception {
        fixtures.recordEvent(shipmentId, "DEPARTED", "IN_TRANSIT", "2026-04-21T06:00:00Z");

        JsonNode late = fixtures.recordEvent(shipmentId, "GATE-IN", "AT_ORIGIN_PORT", "2026-04-20T15:00:00Z");

        assertThat(late.get("outcome").asString()).isEqualTo("OUT_OF_ORDER");
        assertThat(late.get("currentShipmentStatus").asString()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void nothingLeavesDelivered() throws Exception {
        fixtures.recordEvent(shipmentId, "POD", "DELIVERED", "2026-05-12T14:00:00Z");

        JsonNode event = fixtures.recordEvent(shipmentId, "EXTRA", "OUT_FOR_DELIVERY", "2026-05-12T15:00:00Z");

        assertThat(event.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(event.get("currentShipmentStatus").asString()).isEqualTo("DELIVERED");
    }

    @Test
    void concurrentCopiesOfOneCarrierEventStoreItOnce() throws Exception {
        int copies = 16;
        List<MvcResult> results = concurrently(copies,
                i -> eventRequest(shipmentId, "DUP-EVT-1", "PICKED_UP", "2026-04-19T08:00:00Z"));

        long created = results.stream().filter(r -> r.getResponse().getStatus() == 201).count();
        long duplicates = results.stream().filter(r -> r.getResponse().getStatus() == 200).count();
        assertThat(created).isEqualTo(1);
        assertThat(duplicates).isEqualTo(copies - 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tracking_events WHERE shipment_id = ?::uuid AND carrier_event_id = 'DUP-EVT-1'",
                Integer.class, shipmentId)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentEventsAreAllRecordedThroughOptimisticLockRetries() throws Exception {
        int events = 6;
        List<MvcResult> results = concurrently(events,
                i -> eventRequest(shipmentId, "SCAN-" + i, "PICKED_UP", "2026-04-19T08:%02d:00Z".formatted(i)));

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tracking_events WHERE shipment_id = ?::uuid AND carrier_event_id LIKE 'SCAN-%'",
                Integer.class, shipmentId)).isEqualTo(events);
    }

    @Test
    void ifMatchStillGuardsOperatorUpdates() throws Exception {
        mockMvc.perform(eventRequest(shipmentId, "OPS-1", "PICKED_UP", "2026-04-19T08:00:00Z", "\"0\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/precondition-failed"));
    }

    private String trackingNumber() {
        return jdbcTemplate.queryForObject("SELECT tracking_number FROM shipments WHERE id = ?::uuid",
                String.class, shipmentId);
    }

    private List<MvcResult> concurrently(int count,
                                         java.util.function.IntFunction<org.springframework.test.web.servlet.RequestBuilder> request)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(request.apply(index)).andReturn();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
