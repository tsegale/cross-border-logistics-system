package com.freightflow.logistics.events;

import com.freightflow.logistics.support.Relays;
import java.time.Clock;
import com.freightflow.logistics.payment.PaymentCommandRelay;
import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.EventSchemas;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.freightflow.logistics.support.BankingStubs.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Producer side of the event contracts (ADR 0023): every event logistics writes to its outbox matches the committed
 * schema of its type, and carries nothing of the parties (ADR 0024). Every row in the outbox is checked, this test's
 * and whatever earlier tests left.
 */
class EventContractIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentCommandRelay relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private LogisticsFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new LogisticsFixtures(mockMvc, relay);
    }

    @Test
    void everyEventLogisticsWritesMatchesItsSchema() throws Exception {
        UUID paid = UUID.fromString(fixtures.bookAndPay().get("id").asString());
        fixtures.recordEvent(paid.toString(), "pickup-" + paid, "PICKED_UP", Instant.now().minusSeconds(60).toString());

        UUID refused = UUID.fromString(fixtures.bookShipment().get("id").asString());
        BankingStub.SERVER.stubFor(BankingStubs.holdFor(refused)
                .willReturn(respond(BankingStubs.HOLD_INSUFFICIENT_FUNDS)));
        relayFor(refused, paid);

        UUID cancelled = UUID.fromString(fixtures.bookShipment().get("id").asString());
        mockMvc.perform(post("/api/v1/shipments/{id}/cancellation", cancelled).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer postponed order\"}"))
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT event_type, envelope::text AS envelope FROM event_outbox");
        for (Map<String, Object> row : rows) {
            String type = (String) row.get("event_type");
            assertThat(EventSchemas.validate(type, (String) row.get("envelope")))
                    .as("%s: %s", type, row.get("envelope")).isEmpty();
        }
        assertThat(types(paid, refused, cancelled)).containsAll(Arrays.stream(LogisticsEvents.Type.values())
                .map(LogisticsEvents.Type::typeName).toList());
    }

    @Test
    void aShipmentsEventsAreNumberedInTheOrderTheyHappened() throws Exception {
        UUID shipment = UUID.fromString(fixtures.bookAndPay().get("id").asString());
        fixtures.recordEvent(shipment.toString(), "pickup-" + shipment, "PICKED_UP",
                Instant.now().minus(1, ChronoUnit.HOURS).toString());

        List<JsonNode> events = envelopes(shipment);
        assertThat(events).extracting(event -> event.path("type").asString()).containsExactly(
                "shipment.booked", "shipment.status-changed", "tracking-event.recorded", "shipment.status-changed");
        assertThat(events).extracting(event -> event.path("sequence").asLong()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events.get(1).path("payload").path("status").asString()).isEqualTo("BOOKED");
        assertThat(events.get(1).path("actor").isNull()).as("banking's answer, relayed: no person").isTrue();
        assertThat(events.get(1).path("requestId").asString()).isEqualTo("shipment-" + shipment + "-authorise");
        assertThat(events.get(3).path("payload").path("previousStatus").asString()).isEqualTo("BOOKED");
        assertThat(events.get(3).path("payload").path("trackingEventId").asString())
                .isEqualTo(events.get(2).path("payload").path("trackingEventId").asString());
    }

    @Test
    void theBookingEventCarriesNothingOfTheParties() throws Exception {
        UUID shipment = UUID.fromString(fixtures.bookShipment().get("id").asString());

        String booked = jdbcTemplate.queryForObject(
                "SELECT envelope::text FROM event_outbox WHERE aggregate_id = ? AND sequence = 1", String.class, shipment);
        // The fixture's shipper, consignee, contacts, goods and payer account (LogisticsFixtures.bookShipmentRequest).
        assertThat(booked).doesNotContain("Erongo", "Nordsee", "Hafenstrasse", "Fischmarkt", "@", "kingklip",
                LogisticsFixtures.PAYER_ACCOUNT);
    }

    @Test
    void aDuplicateTrackingEventWritesNoEvent() throws Exception {
        UUID shipment = UUID.fromString(fixtures.bookAndPay().get("id").asString());
        String at = Instant.now().minusSeconds(60).toString();
        fixtures.recordEvent(shipment.toString(), "dup-" + shipment, "PICKED_UP", at);
        int before = envelopes(shipment).size();

        mockMvc.perform(LogisticsFixtures.eventRequest(shipment.toString(), "dup-" + shipment, "PICKED_UP", at))
                .andExpect(status().isOk());

        assertThat(envelopes(shipment)).hasSize(before);
    }

    private List<JsonNode> envelopes(UUID aggregateId) {
        return jdbcTemplate.queryForList("SELECT envelope::text FROM event_outbox WHERE aggregate_id = ? ORDER BY sequence",
                String.class, aggregateId).stream().map(JSON::readTree).toList();
    }

    private java.util.Set<String> types(UUID... aggregates) {
        return Arrays.stream(aggregates).flatMap(id -> envelopes(id).stream())
                .map(event -> event.path("type").asString()).collect(java.util.stream.Collectors.toSet());
    }

    /** One relay run for these shipments' commands, however many other tests left due (see {@link Relays}). */
    private void relayFor(Object... shipments) {
        Relays.payments(relay::relayOnce, jdbcTemplate, clock, shipments);
    }
}
