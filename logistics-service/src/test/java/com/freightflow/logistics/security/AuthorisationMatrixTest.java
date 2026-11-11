package com.freightflow.logistics.security;

import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import com.freightflow.logistics.support.TestCallers;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.freightflow.logistics.support.LogisticsFixtures.JSON;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every logistics endpoint x every role x own and foreign resource, against the policy table of ADR 0018. "Own" is a
 * customer of the organisation that booked; "foreign" a customer of another organisation; the carrier is the one
 * assigned to the shipment, the other carrier is not. Something a caller may not see is the same 404 as something
 * that does not exist.
 */
class AuthorisationMatrixTest extends IntegrationTest {

    enum Who {
        OWNER(TestCallers.as(TestCallers.CUSTOMER_NAMIB)),
        FOREIGN(TestCallers.as(TestCallers.CUSTOMER_HANSEATIC)),
        OPERATOR(TestCallers.as(TestCallers.OPERATOR)),
        AUDITOR(TestCallers.as(TestCallers.AUDITOR)),
        CARRIER(TestCallers.as(TestCallers.CARRIER)),
        OTHER_CARRIER(TestCallers.as(TestCallers.OTHER_CARRIER)),
        ANONYMOUS(anonymous());

        final RequestPostProcessor token;

        Who(RequestPostProcessor token) {
            this.token = token;
        }
    }

    /** ADR 0018. Columns: owner, foreign customer, operator, auditor, assigned carrier, other carrier, anonymous. */
    static Stream<Arguments> policy() {
        Map<String, int[]> table = new LinkedHashMap<>();
        //                                          OWN  FOR  OP   AUD  CAR  OCAR ANON
        table.put("POST quote",              new int[] {201, 201, 201, 403, 403, 403, 401});
        table.put("GET quote",               new int[] {200, 404, 200, 200, 403, 403, 401});
        table.put("POST shipment on quote",  new int[] {201, 404, 201, 403, 403, 403, 401});
        table.put("GET shipment",            new int[] {200, 404, 200, 200, 200, 404, 401});
        table.put("GET shipments",           new int[] {200, 200, 200, 200, 200, 200, 401});
        table.put("POST tracking event",     new int[] {403, 403, 201, 403, 201, 404, 401});
        table.put("POST cancellation",       new int[] {200, 404, 200, 403, 403, 403, 401});
        table.put("PUT carrier",             new int[] {403, 403, 200, 403, 403, 403, 401});
        table.put("GET payment commands",    new int[] {403, 403, 200, 200, 403, 403, 401});
        table.put("GET payment command",     new int[] {403, 403, 200, 200, 403, 403, 401});
        table.put("POST payment retry",      new int[] {403, 403, 200, 403, 403, 403, 401});
        table.put("GET public tracking",     new int[] {200, 200, 200, 200, 200, 200, 200});
        table.put("GET api docs",            new int[] {200, 200, 200, 200, 403, 403, 401});
        List<Arguments> cases = new ArrayList<>();
        table.forEach((endpoint, expected) -> {
            for (Who who : Who.values()) {
                cases.add(Arguments.of(endpoint, who, expected[who.ordinal()]));
            }
        });
        return cases.stream();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Fixture(String quote, String shipment, String trackingNumber, String parkedCommand) {
    }

    private Fixture fixture;

    /** Namib's quote and shipment, assigned to carrier-walvis-haulage, with its AUTHORISE command parked. */
    @BeforeEach
    void fixture() throws Exception {
        String quote = quote(Who.OWNER.token);
        JsonNode shipment = book(Who.OWNER, quote);
        String id = shipment.get("id").asString();
        mockMvc.perform(put("/api/v1/shipments/{id}/carrier", id).with(Who.OPERATOR.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrierId\":\"carrier-walvis-haulage\"}"))
                .andExpect(status().isOk());
        String command = jdbcTemplate.queryForObject(
                "UPDATE shipment_payments SET status = 'PARKED' WHERE shipment_id = ?::uuid RETURNING id::text", String.class, id);
        fixture = new Fixture(quote, id, shipment.get("trackingNumber").asString(), command);
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("policy")
    void everyEndpointRoleAndOwnershipMatchesThePolicy(String endpoint, Who who, int expected) throws Exception {
        MvcResult result = mockMvc.perform(request(endpoint, who).with(who.token)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s as %s: %s", endpoint, who, result.getResponse().getContentAsString())
                .isEqualTo(expected);
        if (expected == 404) {
            assertThat(json(result).get("type").asString()).endsWith("-not-found");
        }
    }

    private MockHttpServletRequestBuilder request(String endpoint, Who who) {
        Fixture f = fixture;
        return switch (endpoint) {
            case "POST quote" -> post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(QUOTE);
            case "GET quote" -> get("/api/v1/quotes/{id}", f.quote());
            case "POST shipment on quote" -> post("/api/v1/shipments").header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON).content(booking(f.quote(), who == Who.OPERATOR));
            case "GET shipment" -> get("/api/v1/shipments/{id}", f.shipment());
            case "GET shipments" -> get("/api/v1/shipments");
            case "POST tracking event" -> post("/api/v1/shipments/{id}/tracking-events", f.shipment())
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"carrierEventId":"MATRIX-%s","eventType":"SCAN","targetStatus":"PICKED_UP","location":"NAWVB",
                             "description":"Matrix scan","occurredAt":"%s"}
                            """.formatted(UUID.randomUUID(), Instant.now()));
            case "POST cancellation" -> post("/api/v1/shipments/{id}/cancellation", f.shipment())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Matrix\"}");
            case "PUT carrier" -> put("/api/v1/shipments/{id}/carrier", f.shipment())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"carrierId\":\"carrier-kalahari-freight\"}");
            case "GET payment commands" -> get("/api/v1/payment-commands");
            case "GET payment command" -> get("/api/v1/payment-commands/{id}", f.parkedCommand());
            case "POST payment retry" -> post("/api/v1/payment-commands/{id}/retry", f.parkedCommand());
            case "GET public tracking" -> get("/api/v1/shipments/track/{tn}", f.trackingNumber());
            case "GET api docs" -> get("/docs/logistics/v3/api-docs");
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    @Test
    void listsOnlyShowWhatTheCallerMaySee() throws Exception {
        String hanseaticQuote = quote(Who.FOREIGN.token);
        String hanseaticShipment = book(Who.FOREIGN, hanseaticQuote).get("id").asString();

        assertThat(listed(Who.OWNER)).contains(fixture.shipment()).doesNotContain(hanseaticShipment);
        assertThat(listed(Who.FOREIGN)).contains(hanseaticShipment).doesNotContain(fixture.shipment());
        assertThat(listed(Who.CARRIER)).contains(fixture.shipment()).doesNotContain(hanseaticShipment);
        assertThat(listed(Who.OTHER_CARRIER)).doesNotContain(fixture.shipment(), hanseaticShipment);
        assertThat(listed(Who.AUDITOR)).contains(fixture.shipment(), hanseaticShipment);
    }

    @Test
    void sameKeyFromTwoTenantsIsTwoBookings() throws Exception {
        String key = "shared-" + UUID.randomUUID();
        String hanseaticQuote = quote(Who.FOREIGN.token);
        JsonNode namib = json(mockMvc.perform(post("/api/v1/shipments").with(Who.OWNER.token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(booking(fixture.quote(), false)))
                .andExpect(status().isCreated()).andReturn());
        JsonNode hanseatic = json(mockMvc.perform(post("/api/v1/shipments").with(Who.FOREIGN.token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(booking(hanseaticQuote, false)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(hanseatic.get("id").asString()).isNotEqualTo(namib.get("id").asString());
        assertThat(hanseatic.get("bookingOrganizationId").asString()).isEqualTo(TestCallers.HANSEATIC.toString());
    }

    @Test
    void bookingsTrackingEventsCancellationsAndRetriesRecordWhoMadeThem() throws Exception {
        mockMvc.perform(post("/api/v1/shipments/{id}/tracking-events", fixture.shipment()).with(Who.CARRIER.token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"carrierEventId":"WHO-1","eventType":"SCAN","targetStatus":"PICKED_UP","location":"NAWVB",
                                 "description":"Scan","occurredAt":"%s"}
                                """.formatted(Instant.now())))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/payment-commands/{id}/retry", fixture.parkedCommand()).with(Who.OPERATOR.token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/shipments/{id}/cancellation", fixture.shipment()).with(Who.OWNER.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Changed plans\"}"))
                .andExpect(status().isOk());

        Map<String, Object> shipment = jdbcTemplate.queryForMap(
                "SELECT booked_by_subject, booked_by_client_id, cancelled_by_subject FROM shipments WHERE id = ?::uuid",
                fixture.shipment());
        assertThat(shipment.get("booked_by_subject")).isEqualTo(TestCallers.CUSTOMER_NAMIB.subject());
        assertThat(shipment.get("cancelled_by_subject")).isEqualTo(TestCallers.CUSTOMER_NAMIB.subject());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT recorded_by_client_id FROM tracking_events WHERE carrier_event_id = 'WHO-1'", String.class))
                .isEqualTo("carrier-walvis-haulage");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT retried_by_subject FROM shipment_payments WHERE id = ?::uuid", String.class, fixture.parkedCommand()))
                .isEqualTo(TestCallers.OPERATOR.subject());

        // Operators and auditors see who did it; the customer does not.
        mockMvc.perform(get("/api/v1/shipments/{id}", fixture.shipment()).with(Who.AUDITOR.token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.audit.bookedBy.subject")
                        .value(TestCallers.CUSTOMER_NAMIB.subject()));
        mockMvc.perform(get("/api/v1/shipments/{id}", fixture.shipment()).with(Who.OWNER.token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.audit").doesNotExist());
    }

    @Test
    void theHoldIsChargedToTheOrganisationThatBookedAndNamesWhoBooked() throws Exception {
        mockMvc.perform(post("/api/v1/payment-commands/{id}/retry", fixture.parkedCommand()).with(Who.OPERATOR.token))
                .andExpect(status().isOk());
        com.freightflow.logistics.support.BankingStub.SERVER.stubFor(com.freightflow.logistics.support.BankingStubs.createHold()
                .willReturn(com.freightflow.logistics.support.BankingStubs.respond(com.freightflow.logistics.support.BankingStubs.HOLD_CREATED)));
        relay.relayOnce();

        List<LoggedRequest> holds = BankingStub.SERVER.findAll(postRequestedFor(urlPathEqualTo("/api/v1/holds")));
        JsonNode body = JSON.readTree(holds.getLast().getBodyAsString());
        assertThat(body.get("payerOrganizationId").asString()).isEqualTo(TestCallers.NAMIB.toString());
        assertThat(body.get("onBehalfOfSubject").asString()).isEqualTo(TestCallers.CUSTOMER_NAMIB.subject());
        assertThat(holds.getLast().getHeader("Authorization")).isEqualTo("Bearer test-logistics-token");
    }

    @Autowired
    private com.freightflow.logistics.payment.PaymentCommandRelay relay;

    // ------------------------------------------------------------------ helpers

    private static final String QUOTE = """
            {"origin":"NAWVB","destination":"DEHAM","mode":"SEA_LCL","incoterm":"FCA","payerRole":"CONSIGNEE",
             "packages":[{"lengthCm":120,"widthCm":100,"heightCm":150,"weightKg":500,"quantity":2}]}
            """;

    private String quote(RequestPostProcessor token) throws Exception {
        return json(mockMvc.perform(post("/api/v1/quotes").with(token).contentType(MediaType.APPLICATION_JSON).content(QUOTE))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private JsonNode book(Who who, String quote) throws Exception {
        return json(mockMvc.perform(post("/api/v1/shipments").with(who.token).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(booking(quote, who == Who.OPERATOR)))
                .andExpect(status().isCreated()).andReturn());
    }

    private static String booking(String quote, boolean forNamib) {
        return """
                {"quoteId":"%s","shipper":{"name":"Namib Fisheries","address":"Walvis Bay","contact":"x"},
                 "consignee":{"name":"Hanseatic Seafood","address":"Hamburg","contact":"y"},
                 "payerAccountNumber":"%s","goodsDescription":"Frozen hake","declaredValue":1000.00,
                 "plannedPickupDate":"%s"%s}
                """.formatted(quote, LogisticsFixtures.PAYER_ACCOUNT, LogisticsFixtures.defaultPickup(),
                forNamib ? ",\"bookingOrganizationId\":\"" + TestCallers.NAMIB + "\"" : "");
    }

    private List<String> listed(Who who) throws Exception {
        JsonNode page = json(mockMvc.perform(get("/api/v1/shipments").param("size", "100").with(who.token))
                .andExpect(status().isOk()).andReturn());
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private static String key() {
        return "matrix-" + UUID.randomUUID();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    }
}
