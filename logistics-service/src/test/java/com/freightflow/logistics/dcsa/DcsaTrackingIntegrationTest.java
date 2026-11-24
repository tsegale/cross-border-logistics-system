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
import static com.freightflow.logistics.dcsa.DcsaTestEvents.transport;
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

    private LogisticsFixtures fixtures;

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
        List<String> voyage = List.of(equipment("PICK", container, "NAWVB", now), equipment("GTIN", container, "NAWVB", now),
                equipment("LOAD", container, "NAWVB", now), transport("DEPA", "NAWVB", now),
                transport("ARRI", "DEHAM", now), equipment("DISC", container, "DEHAM", now),
                equipment("GTOT", container, "DEHAM", now), equipment("DROP", container, "DEHAM", now));

        for (String event : voyage) {
            mockMvc.perform(push(CARRIER, SUBSCRIPTION, notification(event), null)).andExpect(status().isNoContent());
            drain();
        }

        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");
        // The six equipment events moved it. The vessel's DEPA and ARRI carry no container: stored, matched to no
        // shipment, ignored.
        assertThat(jdbcTemplate.queryForList("SELECT status FROM dcsa_events WHERE shipment_id = ?", String.class,
                shipment)).hasSize(6).containsOnly("APPLIED");
        assertThat(count("SELECT count(*) FROM tracking_events WHERE shipment_id = ? AND carrier_event_id LIKE 'dcsa:%'",
                shipment)).isEqualTo(6);
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

    /** Processes every pending event, as the scheduled processor would. */
    private void drain() {
        for (int round = 0; round < 10; round++) {
            List<UUID> pending = jdbcTemplate.queryForList(
                    "SELECT id FROM dcsa_events WHERE status = 'PENDING' ORDER BY received_at", UUID.class);
            if (pending.isEmpty()) {
                return;
            }
            pending.forEach(processor::process);
        }
    }

    /** A valid ISO 6346 number no other test uses. */
    private static String container() {
        String first = "FFLU" + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        return first + ContainerNumber.checkDigit(first);
    }

    private String shipmentStatus(UUID shipment) {
        return jdbcTemplate.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipment);
    }

    private long count(String sql, Object argument) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, argument);
        return n == null ? 0 : n;
    }
}
