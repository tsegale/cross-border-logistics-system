package com.freightflow.logistics.dcsa;

import com.freightflow.logistics.payment.PaymentCommandRelay;
import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import com.freightflow.logistics.support.TestCallers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.freightflow.logistics.dcsa.DcsaTestEvents.CARRIER;
import static com.freightflow.logistics.dcsa.DcsaTestEvents.SECRET;
import static com.freightflow.logistics.dcsa.DcsaTestEvents.SUBSCRIPTION;
import static com.freightflow.logistics.dcsa.DcsaTestEvents.equipment;
import static com.freightflow.logistics.dcsa.DcsaTestEvents.notification;
import static com.freightflow.logistics.dcsa.DcsaTestEvents.VESSEL;
import static com.freightflow.logistics.dcsa.DcsaTestEvents.equipmentOnVessel;
import static com.freightflow.logistics.dcsa.DcsaTestEvents.transportOnVessel;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DCSA Track and Trace 2.2 from the carrier to the shipment (ADR 0037): signed pushes from booking to delivery,
 * forged, foreign, replayed, stale and malformed notifications refused or harmless, and the polling fallback.
 */
@TestPropertySource(properties = {
        "freightflow.logistics.dcsa.carriers.carrier-kalahari-freight.subscription-id=" + SUBSCRIPTION,
        "freightflow.logistics.dcsa.carriers.carrier-kalahari-freight.secret=" + SECRET,
        "freightflow.logistics.dcsa.carriers.carrier-kalahari-freight.mode=PUSH",
        "freightflow.logistics.dcsa.carriers.carrier-namib-lines.subscription-id=0d9e6c1a-7a55-4f0f-9e0e-2b8f6c7d5e41",
        "freightflow.logistics.dcsa.carriers.carrier-namib-lines.secret=" + SECRET,
        "freightflow.logistics.dcsa.carriers.carrier-namib-lines.mode=POLL",
        // The tests poll and process themselves; the schedules stay out of the way.
        "freightflow.logistics.dcsa.poll-initial-delay=1h",
        "freightflow.logistics.dcsa.poll-interval=1h"
})
class DcsaTrackingIntegrationTest extends IntegrationTest {

    @DynamicPropertySource
    static void pollingCarrier(DynamicPropertyRegistry registry) {
        registry.add("freightflow.logistics.dcsa.carriers.carrier-namib-lines.base-url",
                () -> BankingStub.baseUrl() + "/carrier");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentCommandRelay relay;

    @Autowired
    private DcsaEventProcessor processor;

    @Autowired
    private DcsaPoller poller;

    @Autowired
    private DcsaProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private java.time.Clock clock;

    private LogisticsFixtures fixtures;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    @BeforeEach
    void fixtures() {
        fixtures = new LogisticsFixtures(mockMvc, relay);
        BankingStub.SERVER.stubFor(BankingStubs.capture().willReturn(BankingStubs.respond(BankingStubs.CAPTURED)));
    }

    @Test
    void aShipmentGoesFromBookingToDeliveryOnSignedDcsaEvents() throws Exception {
        String container = container();
        UUID shipment = shipmentInContainer(CARRIER, container);
        Instant now = Instant.now();
        String voyageNumber = voyage();
        List<String> voyage = List.of(equipment("PICK", container, "NAWVB", now), equipment("GTIN", container, "NAWVB", now),
                equipmentOnVessel("LOAD", container, "NAWVB", now, VESSEL, voyageNumber),
                transportOnVessel("DEPA", "NAWVB", now, VESSEL, voyageNumber),
                transportOnVessel("ARRI", "DEHAM", now, VESSEL, voyageNumber),
                equipmentOnVessel("DISC", container, "DEHAM", now, VESSEL, voyageNumber),
                equipment("GTOT", container, "DEHAM", now), equipment("DROP", container, "DEHAM", now));

        for (String event : voyage) {
            mockMvc.perform(push(CARRIER, SUBSCRIPTION, notification(event), null)).andExpect(status().isNoContent());
            drain();
        }

        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");
        // All eight reached it: the six equipment events by the container, the vessel's DEPA and ARRI by the vessel
        // and voyage its LOAD was reported on.
        assertThat(jdbcTemplate.queryForList("SELECT status FROM dcsa_events WHERE shipment_id = ?", String.class,
                shipment)).hasSize(8).containsOnly("APPLIED");
        assertThat(jdbcTemplate.queryForList("SELECT matched_by FROM dcsa_event_shipments WHERE shipment_id = ?",
                String.class, shipment)).hasSize(8).containsOnly("EQUIPMENT", "TRANSPORT_CALL")
                .filteredOn("TRANSPORT_CALL"::equals).hasSize(2);
        assertThat(count("SELECT count(*) FROM tracking_events WHERE shipment_id = ? AND carrier_event_id LIKE 'dcsa:%'",
                shipment)).isEqualTo(8);
    }

    @Test
    void aForgedOrForeignNotificationIsRefusedAndNothingIsStored() throws Exception {
        String container = container();
        String body = notification(equipment("GTIN", container, "NAWVB", Instant.now()));
        String forged = DcsaSignature.sign(java.util.Base64.getEncoder().encodeToString(
                "an attacker's own key, 32 bytes!".getBytes(StandardCharsets.UTF_8)), body.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(push(CARRIER, SUBSCRIPTION, body, forged)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-webhook-signature"));
        mockMvc.perform(push(CARRIER, UUID.randomUUID().toString(), body, null)).andExpect(status().isUnauthorized());
        mockMvc.perform(push("carrier-nobody-knows", SUBSCRIPTION, body, null)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/dcsa/notifications/{carrier}", CARRIER).contentType(MediaType.APPLICATION_JSON)
                .header("Subscription-ID", SUBSCRIPTION).content(body)).andExpect(status().isUnauthorized());

        assertThat(count("SELECT count(*) FROM dcsa_events WHERE equipment_reference = ?", container)).isZero();
    }

    @Test
    void aReplayedNotificationChangesNothingAndAStaleOneIsRefused() throws Exception {
        String container = container();
        String body = notification(equipment("GTIN", container, "NAWVB", Instant.now()));

        mockMvc.perform(push(CARRIER, SUBSCRIPTION, body, null)).andExpect(status().isNoContent());
        mockMvc.perform(push(CARRIER, SUBSCRIPTION, body, null)).andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM dcsa_events WHERE equipment_reference = ?", container)).isEqualTo(1);

        String stale = notification(equipment("GTIN", container, "NAWVB",
                Instant.now().minus(properties.replayWindow()).minus(Duration.ofMinutes(1))));
        mockMvc.perform(push(CARRIER, SUBSCRIPTION, stale, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/webhook-replay-rejected"));
        String future = notification(equipment("GTIN", container, "NAWVB", Instant.now().plus(Duration.ofHours(1))));
        mockMvc.perform(push(CARRIER, SUBSCRIPTION, future, null)).andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM dcsa_events WHERE equipment_reference = ?", container)).isEqualTo(1);
    }

    @Test
    void everyCarriersRefusalSeriesExistsBeforeItsFirstRefusal() {
        // increase() needs two samples: a series born with the first refusal would never show as an increase.
        for (String outcome : List.of("accepted", "signature-invalid", "replay-rejected")) {
            assertThat(meters.find("freightflow.dcsa.notifications").tag("carrier", "carrier-namib-lines")
                    .tag("outcome", outcome).counter()).as(outcome).isNotNull();
        }
    }

    @Test
    void aBodyThatIsNotADcsaNotificationIsRefused() throws Exception {
        for (String body : List.of("{}", "[]", "[{\"eventType\":\"EQUIPMENT\"}]", "not json")) {
            mockMvc.perform(push(CARRIER, SUBSCRIPTION, body, null)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/malformed-dcsa-event"));
        }
    }

    @Test
    void aCarrierThatDoesNotPushIsPolled() throws Exception {
        String container = container();
        UUID shipment = shipmentInContainer("carrier-namib-lines", container);
        BankingStub.SERVER.stubFor(get(urlPathEqualTo("/carrier/v2/events")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(notification(
                        equipment("PICK", container, "NAWVB", Instant.now()),
                        equipment("GTIN", container, "NAWVB", Instant.now())))));
        DcsaProperties.Carrier carrier = properties.carriers().get("carrier-namib-lines");

        assertThat(poller.poll("carrier-namib-lines", carrier)).isEqualTo(2);
        assertThat(poller.poll("carrier-namib-lines", carrier)).as("the same page again: nothing new").isZero();
        drain();

        assertThat(shipmentStatus(shipment)).isEqualTo("AT_ORIGIN_PORT");
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT channel FROM dcsa_events WHERE shipment_id = ?",
                String.class, shipment)).containsExactly("POLL");

        BankingStub.SERVER.stubFor(get(urlPathEqualTo("/carrier/v2/events")).willReturn(aResponse().withStatus(503)));
        assertThat(poller.poll("carrier-namib-lines", carrier)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT last_error FROM dcsa_poll_cursors WHERE carrier_id = ?",
                String.class, "carrier-namib-lines")).contains("503");
    }

    @Test
    void aContainerNumberIsCheckedOnAssignment() throws Exception {
        String shipment = fixtures.bookAndPay().get("id").asString();

        mockMvc.perform(put("/api/v1/shipments/{id}/carrier", shipment).with(TestCallers.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierId\":\"" + CARRIER + "\",\"containerNumber\":\"CSQU3054384\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-container-number"));
    }

    @Test
    void aVesselsArrivalReachesEveryShipmentOnItsVoyageAndNoOther() throws Exception {
        String voyage = voyage();
        String first = container();
        String second = container();
        String elsewhere = container();
        UUID one = shipmentInContainer(CARRIER, first);
        UUID two = shipmentInContainer(CARRIER, second);
        UUID other = shipmentInContainer(CARRIER, elsewhere);
        Instant loaded = Instant.now().minus(Duration.ofHours(2));
        mockMvc.perform(push(CARRIER, SUBSCRIPTION, notification(
                equipmentOnVessel("LOAD", first, "NAWVB", loaded, VESSEL, voyage),
                equipmentOnVessel("LOAD", second, "NAWVB", loaded, VESSEL, voyage),
                equipmentOnVessel("LOAD", elsewhere, "NAWVB", loaded, VESSEL, voyage())), null))
                .andExpect(status().isNoContent());
        drain();

        mockMvc.perform(push(CARRIER, SUBSCRIPTION, notification(transportOnVessel("ARRI", "DEHAM",
                Instant.now().minus(Duration.ofHours(1)), VESSEL, voyage)), null)).andExpect(status().isNoContent());
        drain();

        java.util.function.Supplier<String> seen = () -> jdbcTemplate.queryForList(
                "SELECT event_type, status, outcome FROM dcsa_events WHERE payload -> 'transportCall' ->> "
                        + "'exportVoyageNumber' = ? ORDER BY received_at", voyage) + " "
                + jdbcTemplate.queryForList("SELECT shipment_id, export_voyage FROM shipment_transport_calls WHERE "
                        + "export_voyage = ?", voyage);
        assertThat(shipmentStatus(one)).as(seen).isEqualTo("AT_DESTINATION_PORT");
        assertThat(shipmentStatus(two)).as(seen).isEqualTo("AT_DESTINATION_PORT");
        assertThat(shipmentStatus(other)).as("loaded on another voyage of the same vessel").isEqualTo("IN_TRANSIT");
    }

    @Test
    void aTransportEventFindsItsShipmentByTheCarriersBookingReference() throws Exception {
        String reference = "KAL" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        String shipment = fixtures.bookAndPay().get("id").asString();
        mockMvc.perform(put("/api/v1/shipments/{id}/carrier", shipment).with(TestCallers.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierId\":\"" + CARRIER + "\",\"carrierBookingReference\":\"" + reference + "\"}"))
                .andExpect(status().isOk());

        // No container yet, so no equipment event could have named the vessel: the booking reference links it.
        mockMvc.perform(push(CARRIER, SUBSCRIPTION, notification(transportOnVessel("DEPA", "NAWVB", Instant.now(),
                VESSEL, voyage(), reference)), null)).andExpect(status().isNoContent());
        drain();

        assertThat(shipmentStatus(UUID.fromString(shipment))).isEqualTo("IN_TRANSIT");
        assertThat(jdbcTemplate.queryForObject("SELECT matched_by FROM dcsa_event_shipments WHERE shipment_id = ?::uuid",
                String.class, shipment)).isEqualTo("BOOKING_REFERENCE");
    }

    @Test
    void aTransportEventNoShipmentIsLinkedToYetIsRetriedAndReachesItWhenItsLoadArrivesLate() throws Exception {
        String voyage = voyage();
        UUID id = UUID.randomUUID();
        // The vessel arrived an hour ago; the carrier's LOAD from two hours ago is the one that comes late.
        String event = transportOnVessel("ARRI", "DEHAM", Instant.now().minus(Duration.ofHours(1)), VESSEL, voyage)
                .replaceFirst("\"eventID\":\"[0-9a-f-]+\"", "\"eventID\":\"" + id + "\"");
        mockMvc.perform(push(CARRIER, SUBSCRIPTION, notification(event), null)).andExpect(status().isNoContent());

        drain();
        // Its LOAD may still be on the way: it waits, with a backoff, rather than being written off at once.
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM dcsa_events WHERE id = ?", String.class, id))
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT next_attempt_at > ? FROM dcsa_events WHERE id = ?",
                Boolean.class, java.sql.Timestamp.from(clock.instant()), id)).isTrue();

        // Due by the service's clock (see dueEvents), not the database's.
        jdbcTemplate.update("UPDATE dcsa_events SET attempts = 7, next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(clock.instant()), id);
        drain();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM dcsa_events WHERE id = ?", String.class, id))
                .isEqualTo("UNMATCHED");
        assertThat(jdbcTemplate.queryForObject("SELECT outcome FROM dcsa_events WHERE id = ?", String.class, id))
                .contains(VESSEL);

        // The LOAD that names the vessel arrives late: the arrival is taken up again and reaches the shipment.
        String container = container();
        UUID shipment = shipmentInContainer(CARRIER, container);
        mockMvc.perform(push(CARRIER, SUBSCRIPTION, notification(equipmentOnVessel("LOAD", container, "NAWVB",
                Instant.now().minus(Duration.ofHours(2)), VESSEL, voyage)), null)).andExpect(status().isNoContent());
        drain();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM dcsa_events WHERE id = ?", String.class, id))
                .isEqualTo("APPLIED");
        assertThat(shipmentStatus(shipment)).isEqualTo("AT_DESTINATION_PORT");
    }

    @Test
    void anEquipmentEventReachesEveryShipmentConsolidatedInTheContainer() throws Exception {
        String container = container();
        UUID one = shipmentInContainer(CARRIER, container);
        UUID two = shipmentInContainer(CARRIER, container);

        mockMvc.perform(push(CARRIER, SUBSCRIPTION, notification(equipment("GTIN", container, "NAWVB", Instant.now())),
                null)).andExpect(status().isNoContent());
        drain();

        assertThat(shipmentStatus(one)).isEqualTo("AT_ORIGIN_PORT");
        assertThat(shipmentStatus(two)).isEqualTo("AT_ORIGIN_PORT");
    }

    @Test
    void aBookingReferenceIsCheckedOnAssignment() throws Exception {
        String shipment = fixtures.bookAndPay().get("id").asString();

        mockMvc.perform(put("/api/v1/shipments/{id}/carrier", shipment).with(TestCallers.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierId\":\"" + CARRIER + "\",\"carrierBookingReference\":\"no spaces allowed\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private UUID shipmentInContainer(String carrier, String container) throws Exception {
        String shipment = fixtures.bookAndPay().get("id").asString();
        mockMvc.perform(put("/api/v1/shipments/{id}/carrier", shipment).with(TestCallers.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierId\":\"" + carrier + "\",\"containerNumber\":\"" + container + "\"}"))
                .andExpect(status().isOk());
        return UUID.fromString(shipment);
    }

    private static RequestBuilder push(String carrier, String subscription, String body, String signature) {
        return post("/api/v1/dcsa/notifications/{carrier}", carrier).contentType(MediaType.APPLICATION_JSON)
                .header("Subscription-ID", subscription).header("API-Version", "2.2.0")
                .header(DcsaSignature.HEADER, signature != null ? signature
                        : DcsaSignature.sign(SECRET, body.getBytes(StandardCharsets.UTF_8)))
                .content(body);
    }

    /** Processes every due event, as the scheduled processor does: claimed, so attempts count. */
    /**
     * Processes every due event, this test's and any other's. Test classes share the database and run in random
     * order, and those whose contexts do not process DCSA events leave theirs pending: a local run of the whole suite
     * left more than the ten batches this used to stop at, and this test's own events were never reached. A processed
     * event is applied, unmatched or retried later, never due again at once, so this ends; the bound only stops a bug
     * from spinning.
     */
    private void drain() {
        for (int round = 0; round < 500 && dueEvents() > 0; round++) {
            processor.processDue();
        }
    }

    /**
     * Due by the service's clock, which stamps and claims the events, not the database's: on Docker Desktop the VM's
     * clock can be a little behind the host's, and an event pushed a moment ago was then not yet due by now(), so the
     * last event before each drain was left pending.
     */
    private long dueEvents() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM dcsa_events WHERE status = 'PENDING' AND next_attempt_at <= ?", Long.class,
                java.sql.Timestamp.from(clock.instant()));
        return n == null ? 0 : n;
    }

    /** A valid ISO 6346 number no other test uses. */
    private static String container() {
        String first = "FFLU" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        return first + ContainerNumber.checkDigit(first);
    }

    /** A voyage number no other test uses. */
    private static String voyage() {
        return String.format("%05dN", ThreadLocalRandom.current().nextInt(100_000));
    }

    private String shipmentStatus(UUID shipment) {
        return jdbcTemplate.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipment);
    }

    private long count(String sql, Object argument) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, argument);
        return n == null ? 0 : n;
    }
}
