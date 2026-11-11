package com.freightflow.logistics.payment;

import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static com.freightflow.logistics.support.BankingStubs.respond;
import static com.freightflow.logistics.support.LogisticsFixtures.eventRequest;
import static com.freightflow.logistics.support.LogisticsFixtures.json;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payment saga against a stubbed banking-service (ADR 0012). The stubs come from {@link BankingStubs} and are
 * checked against banking's contract by BankingContractTest. Real network faults against a real banking-service
 * are in the fault-injection tests.
 */
class PaymentSagaIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentCommandRelay relay;

    @Autowired
    private PaymentCommandRepository commands;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LogisticsFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new LogisticsFixtures(mockMvc, relay);
    }

    @Test
    void bookingWritesAnAuthoriseCommandWithADeterministicKeyInTheSameTransaction() throws Exception {
        UUID shipment = UUID.fromString(fixtures.bookShipment().get("id").asString());

        PaymentCommand command = commands.findByShipmentIdAndCommand(shipment, PaymentCommandType.AUTHORISE).orElseThrow();
        assertThat(command.getStatus()).isEqualTo(PaymentCommandStatus.PENDING);
        assertThat(command.getIdempotencyKey()).isEqualTo("shipment-" + shipment + "-authorise");
        assertThat(command.roundedAmount()).isEqualByComparingTo("524.75");
        assertThat(command.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void acceptedHoldBooksTheShipment() throws Exception {
        String shipment = fixtures.bookShipment().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.createHold().willReturn(respond(BankingStubs.HOLD_CREATED)));

        relay.relayOnce();

        mockMvc.perform(get("/api/v1/shipments/{id}", shipment))
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.payment.status").value("AUTHORISED"))
                .andExpect(jsonPath("$.payment.holdId").value(BankingStubs.HOLD_ID));
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/holds"))
                .withHeader("Idempotency-Key", equalTo("shipment-" + shipment + "-authorise")));
    }

    @Test
    void businessRefusalFailsThePaymentWithBankingsProblemAndIsNotRetried() throws Exception {
        String shipment = fixtures.bookShipment().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.createHold().willReturn(respond(BankingStubs.HOLD_INSUFFICIENT_FUNDS)));

        relay.relayOnce();
        relay.relayOnce();

        mockMvc.perform(get("/api/v1/shipments/{id}", shipment))
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.payment.status").value("FAILED"))
                .andExpect(jsonPath("$.payment.failureType").value("https://freightflow.dev/problems/insufficient-funds"));
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/holds")));
    }

    @Test
    void unknownOutcomeIsRetriedWithTheSameKeyUntilBankingAnswers() throws Exception {
        String shipment = fixtures.bookShipment().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.createHold().willReturn(respond(BankingStubs.BANKING_UNAVAILABLE)));

        relay.relayOnce();
        mockMvc.perform(get("/api/v1/shipments/{id}", shipment)).andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
        PaymentCommand pending = command(shipment, PaymentCommandType.AUTHORISE);
        assertThat(pending.getStatus()).isEqualTo(PaymentCommandStatus.PENDING);
        assertThat(pending.getLastError()).contains("503");

        BankingStub.SERVER.resetAll();
        BankingStub.SERVER.stubFor(BankingStubs.createHold().willReturn(respond(BankingStubs.HOLD_CREATED)));
        makeDue(shipment);
        relay.relayOnce();

        mockMvc.perform(get("/api/v1/shipments/{id}", shipment)).andExpect(jsonPath("$.status").value("BOOKED"));
        BankingStub.SERVER.verify(postRequestedFor(urlPathEqualTo("/api/v1/holds"))
                .withHeader("Idempotency-Key", equalTo("shipment-" + shipment + "-authorise")));
    }

    @Test
    void connectionResetIsAnUnknownOutcomeNotARefusal() throws Exception {
        String shipment = fixtures.bookShipment().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.createHold()
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        relay.relayOnce();

        PaymentCommand pending = command(shipment, PaymentCommandType.AUTHORISE);
        assertThat(pending.getStatus()).isEqualTo(PaymentCommandStatus.PENDING);
        assertThat(pending.getProblemType()).isNull();
    }

    @Test
    void keyInFlightAtBankingIsRetriedNotFailed() throws Exception {
        String shipment = fixtures.bookShipment().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.createHold().willReturn(respond(BankingStubs.HOLD_IN_FLIGHT)));

        relay.relayOnce();

        assertThat(command(shipment, PaymentCommandType.AUTHORISE).getStatus()).isEqualTo(PaymentCommandStatus.PENDING);
    }

    @Test
    void outOfAttemptsTheCommandIsParkedAndAnOperatorCanRetryIt() throws Exception {
        String shipment = fixtures.bookShipment().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.createHold().willReturn(respond(BankingStubs.BANKING_UNAVAILABLE)));
        for (int i = 0; i < 8; i++) {
            makeDue(shipment);
            relay.relayOnce();
        }
        PaymentCommand parked = command(shipment, PaymentCommandType.AUTHORISE);
        assertThat(parked.getStatus()).isEqualTo(PaymentCommandStatus.PARKED);
        mockMvc.perform(get("/api/v1/payment-commands").param("status", "PARKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + parked.getId() + "')].attempts").value(8));

        mockMvc.perform(post("/api/v1/payment-commands/{id}/retry", parked.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
        BankingStub.SERVER.resetAll();
        BankingStub.SERVER.stubFor(BankingStubs.createHold().willReturn(respond(BankingStubs.HOLD_CREATED)));
        relay.relayOnce();

        mockMvc.perform(get("/api/v1/shipments/{id}", shipment)).andExpect(jsonPath("$.status").value("BOOKED"));
        mockMvc.perform(post("/api/v1/payment-commands/{id}/retry", parked.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/payment-command-not-parked"));
    }

    @Test
    void pickupCapturesTheHold() throws Exception {
        String shipment = fixtures.bookAndPay().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.capture().willReturn(respond(BankingStubs.CAPTURED)));

        fixtures.recordEvent(shipment, "PICKUP", "PICKED_UP", "2026-04-19T08:00:00Z");
        assertThat(paymentStatus(shipment)).isEqualTo("CAPTURE_PENDING");
        relay.relayOnce();

        assertThat(paymentStatus(shipment)).isEqualTo("CAPTURED");
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathMatching("/api/v1/holds/.+/capture"))
                .withHeader("Idempotency-Key", equalTo("shipment-" + shipment + "-capture")));
    }

    @Test
    void aMissedPickupScanStillCapturesWhenTheCargoIsSeenFurtherOn() throws Exception {
        String shipment = fixtures.bookAndPay().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.capture().willReturn(respond(BankingStubs.CAPTURED)));

        fixtures.recordEvent(shipment, "DEPARTED", "IN_TRANSIT", "2026-04-21T06:00:00Z");
        relay.relayOnce();

        assertThat(paymentStatus(shipment)).isEqualTo("CAPTURED");
    }

    @Test
    void captureAlreadyDoneAtBankingCountsAsCaptured() throws Exception {
        String shipment = fixtures.bookAndPay().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.capture().willReturn(respond(BankingStubs.CAPTURE_ALREADY_DONE)));

        fixtures.recordEvent(shipment, "PICKUP", "PICKED_UP", "2026-04-19T08:00:00Z");
        relay.relayOnce();

        assertThat(paymentStatus(shipment)).isEqualTo("CAPTURED");
    }

    @Test
    void refusedCaptureNeedsAnOperator() throws Exception {
        String shipment = fixtures.bookAndPay().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.capture().willReturn(respond(BankingStubs.CAPTURE_HOLD_EXPIRED)));

        fixtures.recordEvent(shipment, "PICKUP", "PICKED_UP", "2026-04-19T08:00:00Z");
        relay.relayOnce();

        mockMvc.perform(get("/api/v1/shipments/{id}", shipment))
                .andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.payment.status").value("FAILED"))
                .andExpect(jsonPath("$.payment.failureType").value("https://freightflow.dev/problems/hold-expired"));
    }

    @Test
    void cancellingABookedShipmentReleasesTheHold() throws Exception {
        String shipment = fixtures.bookAndPay().get("id").asString();
        BankingStub.SERVER.stubFor(BankingStubs.release().willReturn(respond(BankingStubs.RELEASED)));

        cancel(shipment);
        assertThat(paymentStatus(shipment)).isEqualTo("RELEASE_PENDING");
        relay.relayOnce();

        assertThat(paymentStatus(shipment)).isEqualTo("RELEASED");
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathMatching("/api/v1/holds/.+/release"))
                .withHeader("Idempotency-Key", equalTo("shipment-" + shipment + "-release")));
    }

    @Test
    void holdThatArrivesAfterCancellationIsReleased() throws Exception {
        String shipment = fixtures.bookShipment().get("id").asString();
        cancel(shipment);
        BankingStub.SERVER.stubFor(BankingStubs.createHold().willReturn(respond(BankingStubs.HOLD_CREATED)));
        BankingStub.SERVER.stubFor(BankingStubs.release().willReturn(respond(BankingStubs.RELEASED)));

        relay.relayOnce();
        relay.relayOnce();

        mockMvc.perform(get("/api/v1/shipments/{id}", shipment))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.status").value("RELEASED"));
    }

    @Test
    void carrierEventCannotBookAShipmentThatIsNotPaid() throws Exception {
        String shipment = fixtures.bookShipment().get("id").asString();

        JsonNode event = json(mockMvc.perform(eventRequest(shipment, "FAKE-PAY", "BOOKED", "2026-04-18T09:00:00Z"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(event.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(event.get("currentShipmentStatus").asString()).isEqualTo("AWAITING_PAYMENT");
    }

    // ------------------------------------------------------------------ helpers

    private void cancel(String shipment) throws Exception {
        mockMvc.perform(post("/api/v1/shipments/{id}/cancellation", shipment)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Changed plans\"}"))
                .andExpect(status().isOk());
    }

    private PaymentCommand command(String shipment, PaymentCommandType type) {
        return commands.findByShipmentIdAndCommand(UUID.fromString(shipment), type).orElseThrow();
    }

    private String paymentStatus(String shipment) {
        return jdbcTemplate.queryForObject("SELECT payment_status FROM shipments WHERE id = ?::uuid", String.class, shipment);
    }

    /** The test does not wait for the backoff: it moves the retry time into the past. */
    private void makeDue(String shipment) {
        jdbcTemplate.update("UPDATE shipment_payments SET next_attempt_at = now() - interval '1 second' "
                + "WHERE shipment_id = ?::uuid AND status = 'PENDING'", shipment);
    }
}
