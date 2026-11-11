package com.freightflow.logistics.payment;

import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import com.freightflow.logistics.support.MutableClock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.freightflow.logistics.support.BankingStubs.respond;
import static com.freightflow.logistics.support.LogisticsFixtures.eventRequest;
import static com.freightflow.logistics.support.LogisticsFixtures.json;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A hold lasts until the planned pickup (ADR 0011), driven by an injected clock: booking asks for an expiry, the
 * renewal job queues extensions as the hold nears its end, the relay sends them, and the shipment records banking's
 * answer, until the cargo is picked up and the hold captured.
 */
@Import(HoldRenewalIntegrationTest.Clocks.class)
class HoldRenewalIntegrationTest extends IntegrationTest {

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentCommandRelay relay;

    @Autowired
    private HoldRenewalJob renewals;

    @Autowired
    private PaymentCommandRepository commands;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LogisticsFixtures fixtures;

    @BeforeEach
    void fixtures() {
        // Test classes share one database: commands and booked shipments other tests left behind would fill the
        // relay's batch and the renewal job's count. Each test here starts from its own shipments only.
        jdbcTemplate.update("UPDATE shipment_payments SET status = 'PARKED' WHERE status = 'PENDING'");
        jdbcTemplate.update("UPDATE shipments SET hold_expires_at = NULL WHERE hold_expires_at IS NOT NULL");
        fixtures = new LogisticsFixtures(mockMvc, relay);
        // Banking answers every hold call with the expiry it was asked for, as the real service does.
        BankingStub.SERVER.stubFor(BankingStubs.createHold().willReturn(echoExpiry(201)));
        BankingStub.SERVER.stubFor(BankingStubs.extend().willReturn(echoExpiry(200)));
        BankingStub.SERVER.stubFor(BankingStubs.capture().willReturn(respond(BankingStubs.CAPTURED)));
    }

    @Test
    void aPickupTwoMonthsAwayIsCoveredByExtensionsUntilTheCaptureAtPickup() throws Exception {
        LocalDate pickup = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(60);
        Instant target = pickup.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofDays(3));
        String shipment = fixtures.bookShipment(pickup).get("id").asString();

        relay.relayOnce();
        Instant first = holdExpiry(shipment);
        assertThat(first).isEqualTo(clock.instant().plus(Duration.ofDays(29)));
        assertThat(requestedExpiries("/api/v1/holds")).containsExactly(first.toString());

        // Nothing is due while the hold has more than three days left.
        clock.advance(Duration.ofDays(25));
        assertThat(renewals.renewDue()).isZero();

        // Two days before expiry: extended as far as one request may reach.
        clock.advance(Duration.ofDays(2));
        assertThat(renewals.renewDue()).isEqualTo(1);
        assertThat(renewals.renewDue()).as("one open extension per shipment").isZero();
        relay.relayOnce();
        Instant second = holdExpiry(shipment);
        assertThat(second).isEqualTo(clock.instant().plus(Duration.ofDays(29)));

        // The next extension reaches the pickup target, and after that nothing more is needed.
        clock.advance(Duration.ofDays(27));
        assertThat(renewals.renewDue()).isEqualTo(1);
        relay.relayOnce();
        assertThat(holdExpiry(shipment)).isEqualTo(target);
        clock.advance(Duration.ofDays(4));
        assertThat(renewals.renewDue()).isZero();

        List<PaymentCommand> extensions = commands.findByShipmentIdOrderByCreatedAtAsc(UUID.fromString(shipment))
                .stream().filter(c -> c.getCommand() == PaymentCommandType.EXTEND).toList();
        assertThat(extensions).hasSize(2).allMatch(c -> c.getStatus() == PaymentCommandStatus.SUCCEEDED);
        assertThat(extensions).extracting(PaymentCommand::getIdempotencyKey).containsExactly(
                PaymentCommands.extensionKey(UUID.fromString(shipment), second),
                PaymentCommands.extensionKey(UUID.fromString(shipment), target));
        assertThat(requestedExpiries("/api/v1/holds/" + BankingStubs.HOLD_ID + "/extension"))
                .containsExactly(second.toString(), target.toString());

        // Picked up on the planned day: the hold is still active and is captured.
        mockMvc.perform(eventRequest(shipment, "PICKUP-1", "PICKED_UP", clock.instant().toString()))
                .andExpect(status().isCreated());
        relay.relayOnce();
        assertThat(paymentOf(shipment).get("status").asString()).isEqualTo("CAPTURED");
    }

    @Test
    void anExtensionLosingTheRaceToTheCaptureIsMoot() throws Exception {
        LocalDate pickup = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(40);
        String shipment = fixtures.bookShipment(pickup).get("id").asString();
        relay.relayOnce();
        Instant first = holdExpiry(shipment);

        clock.advance(Duration.ofDays(27));
        assertThat(renewals.renewDue()).isEqualTo(1);
        BankingStub.SERVER.stubFor(BankingStubs.extend().willReturn(respond(BankingStubs.EXTEND_AFTER_CAPTURE)));
        relay.relayOnce();

        PaymentCommand extension = commands.findByShipmentIdOrderByCreatedAtAsc(UUID.fromString(shipment)).stream()
                .filter(c -> c.getCommand() == PaymentCommandType.EXTEND).findFirst().orElseThrow();
        assertThat(extension.getStatus()).isEqualTo(PaymentCommandStatus.SUCCEEDED);
        assertThat(holdExpiry(shipment)).as("a moot extension records nothing").isEqualTo(first);
        assertThat(paymentOf(shipment).get("status").asString()).isEqualTo("AUTHORISED");
    }

    @Test
    void anExtensionBankingRefusesFlagsThePaymentForAnOperator() throws Exception {
        LocalDate pickup = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(40);
        String shipment = fixtures.bookShipment(pickup).get("id").asString();
        relay.relayOnce();

        clock.advance(Duration.ofDays(27));
        renewals.renewDue();
        BankingStub.SERVER.stubFor(BankingStubs.extend().willReturn(respond(BankingStubs.EXTEND_BEYOND_LIFETIME)));
        relay.relayOnce();

        JsonNode payment = paymentOf(shipment);
        assertThat(payment.get("status").asString()).isEqualTo("FAILED");
        assertThat(payment.get("failureType").asString()).isEqualTo("https://freightflow.dev/problems/invalid-hold");
    }

    @Test
    void shipmentsThatAreNotWaitingForPickupAreLeftAlone() throws Exception {
        LocalDate pickup = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(40);
        String pickedUp = fixtures.bookShipment(pickup).get("id").asString();
        relay.relayOnce();
        mockMvc.perform(eventRequest(pickedUp, "PICKUP-2", "PICKED_UP", clock.instant().toString()))
                .andExpect(status().isCreated());

        clock.advance(Duration.ofDays(27));
        assertThat(renewals.renewDue()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** Responds with the hold, its expiry set to the one the request asked for. */
    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder echoExpiry(int status) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(BankingStubs.hold("ACTIVE", "null")
                        .replace("\"2026-11-18T10:00:00Z\"", "\"{{jsonPath request.body '$.expiresAt'}}\""))
                .withTransformers("response-template");
    }

    private List<String> requestedExpiries(String path) {
        return BankingStub.SERVER.findAll(postRequestedFor(urlPathEqualTo(path))).stream()
                .map(LoggedRequest::getBodyAsString)
                .map(body -> LogisticsFixtures.JSON.readTree(body).get("expiresAt").asString())
                .toList();
    }

    private Instant holdExpiry(String shipment) throws Exception {
        return Instant.parse(paymentOf(shipment).get("holdExpiresAt").asString());
    }

    private JsonNode paymentOf(String shipment) throws Exception {
        return json(mockMvc.perform(get("/api/v1/shipments/{id}", shipment)).andExpect(status().isOk()).andReturn())
                .get("payment");
    }
}
