package com.freightflow.logistics.customs;

import com.freightflow.logistics.support.Relays;
import java.util.List;
import java.time.Instant;
import java.time.Clock;
import com.freightflow.logistics.payment.PaymentCommandRelay;
import com.freightflow.logistics.security.Caller;
import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import com.freightflow.logistics.support.TestCallers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static com.freightflow.logistics.support.BankingStubs.respond;
import static com.freightflow.logistics.support.LogisticsFixtures.json;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A Namibian-origin consignment into Hamburg (ADR 0035, ADR 0036): quoted with the EU-SADC EPA preference and
 * without it, booked with the duties held on top of the freight, captured with them as a disbursement, and settled
 * once two operators agree on customs' assessment. Banking is the WireMock stub, its stubs checked against banking's
 * contract.
 */
class CustomsFlowIntegrationTest extends IntegrationTest {

    private static final Caller SECOND_OPERATOR =
            new Caller("test-operator-2", "freightflow-web", null, Set.of("operator"), Set.of());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentCommandRelay relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Test
    void namibianHakeWithAnApprovedExportersDeclarationIsQuotedDutyFreeAndTheVatIsCollected() throws Exception {
        JsonNode quote = quote("NAWVB", "DEHAM", hake(proof()));
        JsonNode customs = quote.get("customs");

        assertThat(customs.get("market").asString()).isEqualTo("EU");
        assertThat(customs.get("label").asString()).startsWith("Estimate.");
        assertThat(customs.get("tariffDate").asString()).isEqualTo("2026-11-24");
        assertThat(customs.get("estimatedDuties").decimalValue()).isEqualByComparingTo("0.00");
        JsonNode line = customs.get("lines").get(0);
        assertThat(line.get("preferenceApplied").asBoolean()).isTrue();
        assertThat(line.get("preferenceNote").asString()).contains("EU-SADC EPA");
        // 7 % import VAT (Anlage 2 Nr. 3) of the VAT base; the CIF value adds the freight to Hamburg to the goods.
        assertThat(line.get("vatPercent").decimalValue()).isEqualByComparingTo("7");
        assertThat(line.get("customsValue").decimalValue()).isGreaterThan(new BigDecimal("48000.00"));
        assertThat(customs.get("estimatedImportVat").decimalValue()).isEqualByComparingTo(
                line.get("vatBase").decimalValue().multiply(new BigDecimal("0.07")).setScale(2, RoundingMode.HALF_EVEN));
        // FCA, quoted by the consignee: the consignee owes the duties, in EUR, so they are collected with the freight.
        assertThat(customs.get("paidBy").asString()).isEqualTo("CONSIGNEE");
        assertThat(customs.get("collectedWithFreight").asBoolean()).isTrue();
        assertThat(quote.get("amountDueOnBooking").decimalValue()).isEqualByComparingTo(
                quote.get("totalAmount").decimalValue().add(customs.get("estimatedTotal").decimalValue()));
    }

    @Test
    void theSameHakeWithoutAProofOfOriginPaysTheThirdCountryDuty() throws Exception {
        JsonNode line = quote("NAWVB", "DEHAM", hake(null)).get("customs").get("lines").get(0);

        assertThat(line.get("preferenceApplied").asBoolean()).isFalse();
        assertThat(line.get("dutyRate").asString()).isEqualTo("7.5 %");
        assertThat(line.get("duty").decimalValue()).isEqualByComparingTo(
                line.get("customsValue").decimalValue().multiply(new BigDecimal("0.075")).setScale(2, RoundingMode.HALF_EVEN));
    }

    @Test
    void goodsAreCheckedAgainstTheLoadedTariff() throws Exception {
        mockMvc.perform(quoteRequest("NAWVB", "DEHAM", hake(null).replace("0304741100", "03047411")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-hs-code"));
        mockMvc.perform(quoteRequest("NAWVB", "DEHAM", hake(null).replace("0304741100", "0304741990")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/unknown-tariff-code"))
                .andExpect(jsonPath("$.tariffDate").value("2026-11-24"));
    }

    @Test
    void germanFridgesIntoWalvisBayAreEstimatedInNadAndNotCollectedWithEuroFreight() throws Exception {
        BankingStub.SERVER.stubFor(get(urlPathEqualTo("/api/v1/fx-rates")).withQueryParam("base",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("EUR")).willReturn(respond(BankingStubs.FX_RATES_EUR)));
        String fridges = """
                {"hsCode":"84181020","description":"Fridge-freezers, 350 l","originCountry":"DE","quantity":40,
                 "netWeightKg":2400,"customsValue":10000.00,"customsValueCurrency":"EUR",
                 "originProof":{"type":"EUR1","reference":"DE 0099123","issuedOn":"%s"}}
                """.formatted(LocalDate.now(ZoneOffset.UTC));

        JsonNode customs = quote("DEHAM", "NAWVB", fridges).get("customs");

        // FOB 10,000.00 EUR at banking's mid 19.1513 = 191,513.00 NAD, EU rate free; VAT 15 % of FOB + 10 %.
        assertThat(customs.get("market").asString()).isEqualTo("SACU");
        assertThat(customs.get("currency").asString()).isEqualTo("NAD");
        assertThat(customs.get("lines").get(0).get("customsValue").decimalValue()).isEqualByComparingTo("191513.00");
        assertThat(customs.get("estimatedDuties").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(customs.get("estimatedImportVat").decimalValue()).isEqualByComparingTo("31599.64");
        assertThat(customs.get("collectedWithFreight").asBoolean()).as("NAD duties, EUR freight").isFalse();
        assertThat(customs.get("collectedInOwnHold").asBoolean()).as("held in NAD, ADR 0038").isTrue();
    }

    @Test
    void namibianDutiesAreHeldInNadOnTheirOwnCapturedAtPickupAndSettledWithNamra() throws Exception {
        JsonNode quote = quote("DEHAM", "NAWVB", fridges());
        BigDecimal duties = quote.get("customs").get("estimatedTotal").decimalValue();
        BigDecimal freight = quote.get("amountDueOnBooking").decimalValue();
        UUID shipment = book(quote);

        // The NAD duty hold goes first; the EUR freight hold only once it stands.
        assertThat(commands(shipment)).containsExactly("DUTIES AUTHORISE " + duties.setScale(4) + " NAD");
        BankingStub.SERVER.stubFor(BankingStubs.dutyHoldFor(shipment).willReturn(BankingStubs.holdCreated(DUTY_HOLD)));
        BankingStub.SERVER.stubFor(BankingStubs.holdFor(shipment).willReturn(respond(BankingStubs.HOLD_CREATED)));
        relayFor(shipment);
        assertThat(commands(shipment)).containsExactly("DUTIES AUTHORISE " + duties.setScale(4) + " NAD",
                "FREIGHT AUTHORISE " + freight.setScale(4) + " EUR");
        relayFor(shipment);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/shipments/{id}", shipment).with(TestCallers.operator()))
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.payment.status").value("AUTHORISED"))
                .andExpect(jsonPath("$.payment.amount").value(freight.doubleValue()))
                .andExpect(jsonPath("$.payment.dutyHold.status").value("AUTHORISED"))
                .andExpect(jsonPath("$.payment.dutyHold.holdId").value(DUTY_HOLD))
                .andExpect(jsonPath("$.payment.dutyHold.currency").value("NAD"));

        // Picked up: both holds are captured, the duty hold wholly as a disbursement.
        BankingStub.SERVER.stubFor(BankingStubs.capture().willReturn(respond(BankingStubs.CAPTURED)));
        mockMvc.perform(LogisticsFixtures.eventRequest(shipment.toString(), "PICKUP-" + shipment, "PICKED_UP",
                Instant.now().minusSeconds(60).toString())).andExpect(status().isCreated());
        relayFor(shipment);
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/holds/" + DUTY_HOLD + "/capture"))
                .withRequestBody(equalToJson("{\"amount\":" + duties.toPlainString() + ",\"disbursementAmount\":"
                        + duties.toPlainString() + "}")));
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/holds/" + BankingStubs.HOLD_ID
                + "/capture")).withRequestBody(equalToJson("{\"amount\":" + freight.toPlainString()
                + ",\"disbursementAmount\":0.00}")).withHeader("Idempotency-Key",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("shipment-" + shipment + "-capture")));

        // NamRA's notice, in NAD, four eyes; banking settles against the duty hold.
        BigDecimal assessed = duties.add(new BigDecimal("150.00"));
        mockMvc.perform(post("/api/v1/shipments/{id}/customs-assessment", shipment).with(TestCallers.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"duties":0.00,"importVat":%s,"currency":"EUR","authorityReference":"NAMRA-26-0001"}
                                """.formatted(assessed.toPlainString())))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/shipments/{id}/customs-assessment", shipment).with(TestCallers.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"duties":0.00,"importVat":%s,"currency":"NAD","authorityReference":"NAMRA-26-0001"}
                                """.formatted(assessed.toPlainString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.difference").value(150.00));
        mockMvc.perform(post("/api/v1/shipments/{id}/customs-assessment/confirmation", shipment)
                        .with(TestCallers.as(SECOND_OPERATOR)))
                .andExpect(status().isOk());
        BankingStub.SERVER.stubFor(BankingStubs.customsSettlement().willReturn(respond(BankingStubs.CUSTOMS_SETTLED)));
        relayFor(shipment);
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/holds/" + DUTY_HOLD
                + "/customs-settlement")).withHeader("Idempotency-Key",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("shipment-" + shipment + "-duties-customs_settlement")));
        assertThat(jdbcTemplate.queryForObject("SELECT customs_settled_at IS NOT NULL FROM shipments WHERE id = ?",
                Boolean.class, shipment)).isTrue();
    }

    @Test
    void aRefusedDutyHoldFailsTheBookingAndNothingElseIsAskedFor() throws Exception {
        UUID shipment = book(quote("DEHAM", "NAWVB", fridges()));
        BankingStub.SERVER.stubFor(BankingStubs.dutyHoldFor(shipment)
                .willReturn(respond(BankingStubs.HOLD_INSUFFICIENT_FUNDS)));

        relayFor(shipment);

        assertThat(jdbcTemplate.queryForObject("SELECT status || ' ' || duty_hold_status FROM shipments WHERE id = ?",
                String.class, shipment)).isEqualTo("PAYMENT_FAILED FAILED");
        assertThat(commands(shipment)).hasSize(1);
    }

    @Test
    void aRefusedFreightHoldReleasesTheDutyHold() throws Exception {
        UUID shipment = book(quote("DEHAM", "NAWVB", fridges()));
        BankingStub.SERVER.stubFor(BankingStubs.dutyHoldFor(shipment).willReturn(BankingStubs.holdCreated(DUTY_HOLD)));
        BankingStub.SERVER.stubFor(BankingStubs.holdFor(shipment).willReturn(respond(BankingStubs.HOLD_INSUFFICIENT_FUNDS)));
        BankingStub.SERVER.stubFor(BankingStubs.release().willReturn(respond(BankingStubs.RELEASED)));

        relayFor(shipment);
        relayFor(shipment);
        relayFor(shipment);

        assertThat(jdbcTemplate.queryForObject("SELECT status || ' ' || duty_hold_status FROM shipments WHERE id = ?",
                String.class, shipment)).isEqualTo("PAYMENT_FAILED RELEASED");
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/holds/" + DUTY_HOLD + "/release")));
    }

    @Test
    void bookedCapturedAndSettledWithFourEyes() throws Exception {
        JsonNode quote = quote("NAWVB", "DEHAM", hake(proof()));
        BigDecimal estimate = quote.get("customs").get("estimatedTotal").decimalValue();
        BigDecimal due = quote.get("amountDueOnBooking").decimalValue();
        UUID shipment = UUID.fromString(json(mockMvc.perform(LogisticsFixtures.bookShipmentRequest(
                quote.get("id").asString(), "customs-" + UUID.randomUUID())).andExpect(status().isCreated()).andReturn())
                .get("id").asString());
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM shipment_payments WHERE shipment_id = ? "
                + "AND command = 'AUTHORISE'", BigDecimal.class, shipment)).as("freight and duties held")
                .isEqualByComparingTo(due);

        BankingStub.SERVER.stubFor(BankingStubs.holdFor(shipment).willReturn(respond(BankingStubs.HOLD_CREATED)));
        relayFor(shipment);
        BankingStub.SERVER.stubFor(BankingStubs.capture().willReturn(respond(BankingStubs.CAPTURED)));
        mockMvc.perform(LogisticsFixtures.eventRequest(shipment.toString(), "PICKUP-" + shipment, "PICKED_UP",
                "2026-04-19T08:00:00Z")).andExpect(status().isCreated());
        relayFor(shipment);
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathMatching("/api/v1/holds/.+/capture"))
                .withRequestBody(equalToJson("{\"amount\":" + due.toPlainString() + ",\"disbursementAmount\":"
                        + estimate.toPlainString() + "}")));

        // One operator records the notice; the same one cannot confirm it; a second does, and banking settles.
        BigDecimal assessedVat = estimate.add(new BigDecimal("10.00"));
        mockMvc.perform(post("/api/v1/shipments/{id}/customs-assessment", shipment).with(TestCallers.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"duties":0.00,"importVat":%s,"currency":"EUR","authorityReference":"26DE470100123456E1"}
                                """.formatted(assessedVat.toPlainString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.difference").value(10.00));
        mockMvc.perform(post("/api/v1/shipments/{id}/customs-assessment/confirmation", shipment)
                        .with(TestCallers.operator()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/four-eyes-required"));
        mockMvc.perform(post("/api/v1/shipments/{id}/customs-assessment/confirmation", shipment)
                        .with(TestCallers.as(SECOND_OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        BankingStub.SERVER.stubFor(BankingStubs.customsSettlement().willReturn(respond(BankingStubs.CUSTOMS_SETTLED)));
        relayFor(shipment);
        BankingStub.SERVER.verify(1, postRequestedFor(urlPathMatching("/api/v1/holds/.+/customs-settlement"))
                .withRequestBody(equalToJson("{\"assessedAmount\":" + assessedVat.toPlainString()
                        + ",\"authorityReference\":\"26DE470100123456E1\"}")));
        assertThat(jdbcTemplate.queryForObject("SELECT customs_settled_at IS NOT NULL FROM shipments WHERE id = ?",
                Boolean.class, shipment)).isTrue();
    }

    @Test
    void aShipmentWhoseDutiesWereNotCollectedHasNoAssessment() throws Exception {
        String quoteId = new LogisticsFixtures(mockMvc).createQuote();
        UUID shipment = UUID.fromString(json(mockMvc.perform(LogisticsFixtures.bookShipmentRequest(quoteId,
                "customs-" + UUID.randomUUID())).andExpect(status().isCreated()).andReturn()).get("id").asString());

        mockMvc.perform(post("/api/v1/shipments/{id}/customs-assessment", shipment).with(TestCallers.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"duties":1.00,"importVat":1.00,"currency":"EUR","authorityReference":"X"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/customs-assessment-conflict"));
    }

    private static final String DUTY_HOLD = "01a1c900-0000-7000-8000-00000000d0d0";

    /** German fridge-freezers into Walvis Bay: NAD 31,599.64 of Namibian VAT, at banking's stubbed rate. */
    private String fridges() {
        BankingStub.SERVER.stubFor(get(urlPathEqualTo("/api/v1/fx-rates")).withQueryParam("base",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("EUR")).willReturn(respond(BankingStubs.FX_RATES_EUR)));
        return """
                {"hsCode":"84181020","description":"Fridge-freezers, 350 l","originCountry":"DE","quantity":40,
                 "netWeightKg":2400,"customsValue":10000.00,"customsValueCurrency":"EUR",
                 "originProof":{"type":"EUR1","reference":"DE 0099123","issuedOn":"%s"}}
                """.formatted(LocalDate.now(ZoneOffset.UTC));
    }

    private UUID book(JsonNode quote) throws Exception {
        return UUID.fromString(json(mockMvc.perform(LogisticsFixtures.bookShipmentRequest(quote.get("id").asString(),
                "customs-" + UUID.randomUUID())).andExpect(status().isCreated()).andReturn()).get("id").asString());
    }

    /** The shipment's payment commands, oldest first: hold, command, amount, currency. */
    private List<String> commands(UUID shipment) {
        return jdbcTemplate.queryForList("SELECT hold_kind || ' ' || command || ' ' || amount || ' ' || currency "
                + "FROM shipment_payments WHERE shipment_id = ? ORDER BY created_at", String.class, shipment);
    }

    private JsonNode quote(String origin, String destination, String goods) throws Exception {
        return json(mockMvc.perform(quoteRequest(origin, destination, goods)).andExpect(status().isCreated()).andReturn());
    }

    private static org.springframework.test.web.servlet.RequestBuilder quoteRequest(String origin, String destination,
                                                                                     String goods) {
        return post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content("""
                {"origin":"%s","destination":"%s","mode":"SEA_LCL","incoterm":"FCA","payerRole":"CONSIGNEE",
                 "packages":[{"lengthCm":120,"widthCm":100,"heightCm":150,"weightKg":500,"quantity":2}],
                 "goods":[%s]}
                """.formatted(origin, destination, goods));
    }

    private static String hake(String proof) {
        return """
                {"hsCode":"0304741100","description":"Frozen Cape hake fillets, 10 kg cartons","originCountry":"NA",
                 "quantity":1200,"netWeightKg":12000,"customsValue":48000.00,"customsValueCurrency":"EUR"%s}
                """.formatted(proof == null ? "" : ",\"originProof\":" + proof);
    }

    private static String proof() {
        return """
                {"type":"ORIGIN_DECLARATION","reference":"INV-2026-0412","issuedOn":"%s","approvedExporterNumber":"NA/AE/0042"}
                """.formatted(LocalDate.now(ZoneOffset.UTC));
    }

    /** One relay run for these shipments' commands, however many other tests left due (see {@link Relays}). */
    private void relayFor(Object... shipments) {
        Relays.payments(relay::relayOnce, jdbcTemplate, clock, shipments);
    }
}
