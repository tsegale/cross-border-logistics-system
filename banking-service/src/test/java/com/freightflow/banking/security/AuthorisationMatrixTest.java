package com.freightflow.banking.security;

import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.support.TestCallers;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every banking endpoint x every role x own and foreign resource, against the policy table of ADR 0018. A combination
 * the table does not allow fails the test, and so does a refusal it does allow. "Own" is a customer of the
 * organisation that owns the resource; "foreign" a customer of another organisation, who must get the same 404 as
 * for something that does not exist.
 */
class AuthorisationMatrixTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    enum Who {
        OWNER(TestCallers.as(TestCallers.CUSTOMER_NAMIB)),
        FOREIGN(TestCallers.as(TestCallers.CUSTOMER_HANSEATIC)),
        OPERATOR(TestCallers.as(TestCallers.OPERATOR)),
        AUDITOR(TestCallers.as(TestCallers.AUDITOR)),
        CARRIER(TestCallers.as(TestCallers.CARRIER)),
        LOGISTICS(TestCallers.as(TestCallers.LOGISTICS)),
        ANONYMOUS(anonymous());

        final RequestPostProcessor token;

        Who(RequestPostProcessor token) {
            this.token = token;
        }
    }

    /**
     * The policy table (ADR 0018). Columns: owner, foreign customer, operator, auditor, carrier, logistics-service,
     * anonymous. A foreign customer creating something gets what any customer gets; reading or moving someone else's
     * money is a 404.
     */
    static Stream<Arguments> policy() {
        Map<String, int[]> table = new java.util.LinkedHashMap<>();
        //                                              OWN  FOR  OP   AUD  CAR  LOG  ANON
        table.put("GET account",                 new int[] {200, 404, 200, 200, 403, 403, 401});
        table.put("GET statement",               new int[] {200, 404, 200, 200, 403, 403, 401});
        table.put("POST account for owner org",  new int[] {201, 403, 201, 403, 403, 403, 401});
        table.put("POST deposit",                new int[] {403, 403, 201, 403, 403, 403, 401});
        table.put("POST transfer from account",  new int[] {201, 404, 201, 403, 403, 403, 401});
        table.put("GET transfer",                new int[] {200, 404, 200, 200, 403, 403, 401});
        table.put("POST fx quote",               new int[] {201, 201, 201, 403, 403, 403, 401});
        table.put("GET fx quote",                new int[] {200, 404, 200, 200, 403, 403, 401});
        table.put("POST transfer with fx quote", new int[] {201, 404, 201, 403, 403, 403, 401});
        table.put("GET fx rates",                new int[] {200, 200, 200, 200, 200, 200, 401});
        table.put("GET journal entry",           new int[] {200, 404, 200, 200, 403, 403, 401});
        table.put("POST reversal",               new int[] {403, 403, 201, 403, 403, 403, 401});
        table.put("GET reconciliation",          new int[] {403, 403, 200, 200, 403, 403, 401});
        table.put("POST hold",                   new int[] {403, 403, 403, 403, 403, 201, 401});
        table.put("GET hold",                    new int[] {403, 403, 403, 403, 403, 200, 401});
        table.put("GET holds by reference",      new int[] {403, 403, 403, 403, 403, 200, 401});
        table.put("POST capture",                new int[] {403, 403, 403, 403, 403, 200, 401});
        table.put("POST extension",              new int[] {403, 403, 403, 403, 403, 200, 401});
        table.put("POST release",                new int[] {403, 403, 403, 403, 403, 200, 401});
        table.put("GET api docs",                new int[] {200, 200, 200, 200, 403, 403, 401});
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

    private Fixture fixture;

    /** Namib's two EUR accounts and one NAD account, funded; a Namib-to-Namib transfer, quote and hold. */
    private record Fixture(String eur, String eur2, String nad, String foreignEur, String transferId, String depositEntry,
                           String fxQuote, String holdId) {
    }

    @BeforeEach
    void fixture() throws Exception {
        String eur = account("EUR", TestCallers.NAMIB);
        String eur2 = account("EUR", TestCallers.NAMIB);
        String nad = account("NAD", TestCallers.NAMIB);
        String foreignEur = account("EUR", TestCallers.HANSEATIC);
        deposit(eur, "1000.00", "EUR");
        // A deposit nothing is spent from, so the operator's reversal of it always has the money to reverse.
        String depositEntry = deposit(eur2, "50.00", "EUR");
        deposit(nad, "10000.00", "NAD");
        String transfer = json(mockMvc.perform(post("/api/v1/transfers").with(Who.OWNER.token).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(transferBody(eur, eur2, "1.00", "EUR", null)))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        String quote = fxQuote(Who.OWNER.token);
        String hold = json(mockMvc.perform(post("/api/v1/holds").with(Who.LOGISTICS.token).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(holdBody(eur, "10.00")))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        fixture = new Fixture(eur, eur2, nad, foreignEur, transfer, depositEntry, quote, hold);
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("policy")
    void everyEndpointRoleAndOwnershipMatchesThePolicy(String endpoint, Who who, int expected) throws Exception {
        MvcResult result = mockMvc.perform(request(endpoint, who).with(who.token)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s as %s: %s", endpoint, who, result.getResponse().getContentAsString())
                .isEqualTo(expected);
        if (expected == 404) {
            // Indistinguishable from an id that does not exist.
            assertThat(json(result).get("type").asString()).endsWith("-not-found");
        }
    }

    private MockHttpServletRequestBuilder request(String endpoint, Who who) throws Exception {
        Fixture f = fixture;
        return switch (endpoint) {
            case "GET account" -> get("/api/v1/accounts/{id}", f.eur());
            case "GET statement" -> get("/api/v1/accounts/{id}/postings", f.eur());
            case "POST account for owner org" -> post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"EUR\",\"organizationId\":\"" + TestCallers.NAMIB + "\"}");
            case "POST deposit" -> post("/api/v1/accounts/{id}/deposits", f.eur()).header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":5.00,\"currency\":\"EUR\",\"reference\":\"MATRIX\"}");
            case "POST transfer from account" -> post("/api/v1/transfers").header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON).content(transferBody(f.eur(), f.foreignEur(), "1.00", "EUR", null));
            case "GET transfer" -> get("/api/v1/transfers/{id}", f.transferId());
            case "POST fx quote" -> post("/api/v1/fx-quotes").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sellCurrency\":\"NAD\",\"buyCurrency\":\"EUR\",\"sellAmount\":100.00}");
            case "GET fx quote" -> get("/api/v1/fx-quotes/{id}", f.fxQuote());
            case "POST transfer with fx quote" -> transferWithQuote(who);
            case "GET fx rates" -> get("/api/v1/fx-rates").param("base", "NAD");
            case "GET journal entry" -> get("/api/v1/journal-entries/{id}", f.depositEntry());
            case "POST reversal" -> post("/api/v1/journal-entries/{id}/reversals", f.depositEntry())
                    .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"MATRIX\"}");
            case "GET reconciliation" -> get("/api/v1/ledger/reconciliation");
            case "POST hold" -> post("/api/v1/holds").header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON).content(holdBody(f.eur(), "1.00"));
            case "GET hold" -> get("/api/v1/holds/{id}", f.holdId());
            case "GET holds by reference" -> get("/api/v1/holds").param("reference", "MATRIX");
            case "POST capture" -> post("/api/v1/holds/{id}/capture", f.holdId()).header("Idempotency-Key", key());
            case "POST extension" -> post("/api/v1/holds/{id}/extension", f.holdId()).header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expiresAt\":\"" + java.time.Instant.now().plus(java.time.Duration.ofDays(9)) + "\"}");
            case "POST release" -> post("/api/v1/holds/{id}/release", f.holdId()).header("Idempotency-Key", key());
            case "GET api docs" -> get("/docs/banking/v3/api-docs");
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    /** A NAD-to-EUR transfer with a quote this caller made, where it may make one; else the owner's quote. */
    private MockHttpServletRequestBuilder transferWithQuote(Who who) throws Exception {
        String quote = (who == Who.OWNER || who == Who.OPERATOR) ? fxQuote(who.token) : fixture.fxQuote();
        // A foreign customer tries Namib's quote and Namib's account: both are someone else's, so 404.
        return post("/api/v1/transfers").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(fixture.nad(), fixture.eur(), "100.00", "NAD", quote));
    }

    @Test
    void sameKeyFromTwoTenantsIsTwoRequestsAndNeitherSeesTheOthersResponse() throws Exception {
        String hanseaticEur = fixture.foreignEur();
        String hanseaticEur2 = account("EUR", TestCallers.HANSEATIC);
        deposit(hanseaticEur, "500.00", "EUR");
        String key = "shared-key-" + UUID.randomUUID();

        JsonNode namib = json(mockMvc.perform(post("/api/v1/transfers").with(Who.OWNER.token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(transferBody(fixture.eur(), fixture.eur2(), "7.00", "EUR", null)))
                .andExpect(status().isCreated()).andReturn());
        // Same key, another tenant, another body: not a 422 mismatch, not Namib's stored response, a transfer of its own.
        JsonNode hanseatic = json(mockMvc.perform(post("/api/v1/transfers").with(Who.FOREIGN.token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(transferBody(hanseaticEur, hanseaticEur2, "3.00", "EUR", null)))
                .andExpect(status().isCreated()).andReturn());

        assertThat(hanseatic.get("id").asString()).isNotEqualTo(namib.get("id").asString());
        assertThat(hanseatic.get("sourceAmount").decimalValue()).isEqualByComparingTo("3.00");
        // Each tenant's replay returns its own response.
        mockMvc.perform(post("/api/v1/transfers").with(Who.OWNER.token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody(fixture.eur(), fixture.eur2(), "7.00", "EUR", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(namib.get("id").asString()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE scope = 'transfer' AND idempotency_key = ?", Integer.class, key))
                .isEqualTo(2);
    }

    @Test
    void everyMoneyMovementRecordsWhoMadeItAndOnlyStaffSeeIt() throws Exception {
        UUID entry = UUID.fromString(fixture.depositEntry());
        Map<String, Object> actor = jdbcTemplate.queryForMap(
                "SELECT actor_subject, actor_client_id, actor_organization_id FROM journal_entries WHERE id = ?", entry);
        assertThat(actor.get("actor_subject")).isEqualTo(TestCallers.OPERATOR.subject());
        assertThat(actor.get("actor_client_id")).isEqualTo("freightflow-web");

        Map<String, Object> transfer = jdbcTemplate.queryForMap("""
                SELECT je.actor_subject, je.actor_organization_id FROM transfers t
                JOIN journal_entries je ON je.id = t.journal_entry_id WHERE t.id = ?::uuid
                """, fixture.transferId());
        assertThat(transfer.get("actor_subject")).isEqualTo(TestCallers.CUSTOMER_NAMIB.subject());
        assertThat(transfer.get("actor_organization_id")).isEqualTo(TestCallers.NAMIB);

        Map<String, Object> hold = jdbcTemplate.queryForMap(
                "SELECT actor_client_id, on_behalf_of_organization_id, on_behalf_of_subject FROM holds WHERE id = ?::uuid",
                fixture.holdId());
        assertThat(hold.get("actor_client_id")).isEqualTo("logistics-service");
        assertThat(hold.get("on_behalf_of_organization_id")).isEqualTo(TestCallers.NAMIB);
        assertThat(hold.get("on_behalf_of_subject")).isEqualTo("booker-1");

        mockMvc.perform(get("/api/v1/journal-entries/{id}", entry).with(Who.AUDITOR.token))
                .andExpect(jsonPath("$.actor.subject").value(TestCallers.OPERATOR.subject()));
        mockMvc.perform(get("/api/v1/journal-entries/{id}", entry).with(Who.OWNER.token))
                .andExpect(jsonPath("$.actor").doesNotExist());
        mockMvc.perform(get("/api/v1/accounts/{id}/postings", fixture.eur2()).with(Who.AUDITOR.token))
                .andExpect(jsonPath("$.items[0].actor.clientId").exists());
        mockMvc.perform(get("/api/v1/accounts/{id}/postings", fixture.eur2()).with(Who.OWNER.token))
                .andExpect(jsonPath("$.items[0].actor").doesNotExist());
    }

    @Test
    void aJournalEntryWithoutAnActorIsRefusedByTheDatabase() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO journal_entries (id, entry_type, reference, created_at) VALUES (gen_random_uuid(), 'DEPOSIT', 'X', now())"))
                .hasMessageContaining("no actor");
    }

    // ------------------------------------------------------------------ helpers

    private String account(String currency, UUID organization) throws Exception {
        return json(mockMvc.perform(post("/api/v1/accounts").with(Who.OPERATOR.token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"" + currency + "\",\"organizationId\":\"" + organization + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("accountNumber").asString();
    }

    private String deposit(String account, String amount, String currency) throws Exception {
        return json(mockMvc.perform(post("/api/v1/accounts/{id}/deposits", account).with(Who.OPERATOR.token)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"currency\":\"" + currency + "\",\"reference\":\"SEED\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private String fxQuote(RequestPostProcessor token) throws Exception {
        return json(mockMvc.perform(post("/api/v1/fx-quotes").with(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellCurrency\":\"NAD\",\"buyCurrency\":\"EUR\",\"sellAmount\":100.00}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private static String transferBody(String source, String target, String amount, String currency, String quote) {
        return "{\"sourceAccount\":\"%s\",\"targetAccount\":\"%s\",\"amount\":%s,\"currency\":\"%s\",\"reference\":\"MATRIX\"%s}"
                .formatted(source, target, amount, currency, quote == null ? "" : ",\"fxQuoteId\":\"" + quote + "\"");
    }

    private static String holdBody(String payer, String amount) {
        return ("{\"payerAccount\":\"%s\",\"amount\":%s,\"currency\":\"EUR\",\"reference\":\"MATRIX\","
                + "\"payerOrganizationId\":\"%s\",\"onBehalfOfSubject\":\"booker-1\"}").formatted(payer, amount, TestCallers.NAMIB);
    }

    private static String key() {
        return "matrix-" + UUID.randomUUID();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    @SuppressWarnings("unused")
    private static List<String> names() {
        return Arrays.stream(Who.values()).map(Enum::name).toList();
    }
}
