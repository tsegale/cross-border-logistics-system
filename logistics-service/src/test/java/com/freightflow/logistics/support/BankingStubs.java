package com.freightflow.logistics.support;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * Every banking-service interaction logistics' tests stub, in one place. {@code BankingContractTest} validates each
 * {@link Interaction} against banking's committed OpenAPI document (ADR 0014), so a stub banking would never
 * answer fails the build instead of making a test pass.
 */
public final class BankingStubs {

    private BankingStubs() {
    }

    /** One request and its response, as they would cross the wire. */
    public record Interaction(String name, String method, String path, String requestBody, int status,
                              String contentType, String responseBody) {
    }

    public static final String HOLD_ID = "01a1c900-0000-7000-8000-000000000001";

    public static String hold(String status, String captured) {
        return """
                {
                  "id": "%s",
                  "payerAccountId": "01a1c900-0000-7000-8000-0000000000aa",
                  "payerAccountNumber": "FF49EUR000001002",
                  "reference": "SHIPMENT 01a1c900-0000-7000-8000-0000000000bb",
                  "amount": 524.75,
                  "currency": "EUR",
                  "payerAmount": 524.75,
                  "payerCurrency": "EUR",
                  "fxRate": null,
                  "status": "%s",
                  "capturedAmount": %s,
                  "capturedPayerAmount": %s,
                  "captureJournalEntryId": %s,
                  "expiresAt": "2026-11-18T10:00:00Z",
                  "closedAt": %s,
                  "createdAt": "2026-11-11T10:00:00Z"
                }
                """.formatted(HOLD_ID, status, captured, captured,
                captured.equals("null") ? "null" : "\"01a1c900-0000-7000-8000-0000000000cc\"",
                status.equals("ACTIVE") ? "null" : "\"2026-11-12T10:00:00Z\"");
    }

    public static String problem(int status, String slug, String title, String extra) {
        return """
                {"type":"https://freightflow.dev/problems/%s","title":"%s","status":%d,"detail":"%s"%s}
                """.formatted(slug, title, status, title, extra == null ? "" : "," + extra).trim();
    }

    public static final String HOLD_REQUEST =
            "{\"payerAccount\":\"FF49EUR000001002\",\"amount\":524.75,\"currency\":\"EUR\",\"reference\":\"SHIPMENT x\","
                    + "\"payerOrganizationId\":\"0193a0e0-0000-7000-8000-00000000a001\",\"onBehalfOfSubject\":\"test-operator\"}";

    public static final Interaction HOLD_CREATED = new Interaction("hold created", "POST", "/api/v1/holds",
            HOLD_REQUEST, 201, "application/json", hold("ACTIVE", "null"));
    public static final Interaction HOLD_INSUFFICIENT_FUNDS = new Interaction("hold refused: insufficient funds",
            "POST", "/api/v1/holds", HOLD_REQUEST, 409, "application/problem+json",
            problem(409, "insufficient-funds", "Insufficient funds",
                    "\"accountNumber\":\"FF49EUR000001002\",\"currency\":\"EUR\",\"availableBalance\":10.00,\"requestedAmount\":524.75"));
    public static final Interaction HOLD_ACCOUNT_NOT_FOUND = new Interaction("hold refused: unknown account",
            "POST", "/api/v1/holds", HOLD_REQUEST, 404, "application/problem+json",
            problem(404, "account-not-found", "Account not found", "\"account\":\"FF49EUR000001002\""));
    public static final Interaction HOLD_IN_FLIGHT = new Interaction("hold key in flight", "POST", "/api/v1/holds",
            HOLD_REQUEST, 409, "application/problem+json",
            problem(409, "idempotency-key-in-flight", "Idempotency-Key request in flight", "\"idempotencyKey\":\"k\""));
    public static final Interaction BANKING_UNAVAILABLE = new Interaction("banking database down", "POST",
            "/api/v1/holds", HOLD_REQUEST, 503, "application/problem+json",
            problem(503, "database-unavailable", "Temporarily unavailable", null));
    public static final Interaction CAPTURED = new Interaction("hold captured", "POST",
            "/api/v1/holds/" + HOLD_ID + "/capture", "{\"amount\":524.75}", 200, "application/json",
            hold("CAPTURED", "524.75"));
    public static final Interaction CAPTURE_ALREADY_DONE = new Interaction("capture already done", "POST",
            "/api/v1/holds/" + HOLD_ID + "/capture", "{\"amount\":524.75}", 409, "application/problem+json",
            problem(409, "hold-not-active", "Hold is not active", "\"holdId\":\"" + HOLD_ID + "\",\"holdStatus\":\"CAPTURED\""));
    public static final Interaction CAPTURE_HOLD_EXPIRED = new Interaction("capture of an expired hold", "POST",
            "/api/v1/holds/" + HOLD_ID + "/capture", "{\"amount\":524.75}", 409, "application/problem+json",
            problem(409, "hold-expired", "Hold has expired",
                    "\"holdId\":\"" + HOLD_ID + "\",\"expiresAt\":\"2026-11-18T10:00:00Z\""));
    public static final Interaction EXTENDED = new Interaction("hold extended", "POST",
            "/api/v1/holds/" + HOLD_ID + "/extension", "{\"expiresAt\":\"2026-12-10T00:00:00Z\"}", 200,
            "application/json", hold("ACTIVE", "null").replace("2026-11-18T10:00:00Z", "2026-12-10T00:00:00Z"));
    public static final Interaction EXTEND_AFTER_CAPTURE = new Interaction("extension of a captured hold", "POST",
            "/api/v1/holds/" + HOLD_ID + "/extension", "{\"expiresAt\":\"2026-12-10T00:00:00Z\"}", 409,
            "application/problem+json",
            problem(409, "hold-not-active", "Hold is not active", "\"holdId\":\"" + HOLD_ID + "\",\"holdStatus\":\"CAPTURED\""));
    public static final Interaction EXTEND_BEYOND_LIFETIME = new Interaction("extension beyond the hold's lifetime",
            "POST", "/api/v1/holds/" + HOLD_ID + "/extension", "{\"expiresAt\":\"2026-12-10T00:00:00Z\"}", 422,
            "application/problem+json", problem(422, "invalid-hold", "Invalid hold", null));
    public static final Interaction RELEASED = new Interaction("hold released", "POST",
            "/api/v1/holds/" + HOLD_ID + "/release", null, 200, "application/json", hold("RELEASED", "null"));
    public static final Interaction FX_RATES_NAD = new Interaction("indicative rates from NAD", "GET",
            "/api/v1/fx-rates?base=NAD", null, 200, "application/json", """
            {"base":"NAD","asOf":"2026-11-10T09:00:00Z","source":"static-configuration","rates":[
              {"currency":"EUR","midRate":0.05000000,"spread":0.005000,"customerRate":0.04975000},
              {"currency":"ZAR","midRate":1.00000000,"spread":0.000000,"customerRate":1.00000000}]}
            """);

    public static List<Interaction> all() {
        return List.of(HOLD_CREATED, HOLD_INSUFFICIENT_FUNDS, HOLD_ACCOUNT_NOT_FOUND, HOLD_IN_FLIGHT,
                BANKING_UNAVAILABLE, CAPTURED, CAPTURE_ALREADY_DONE, CAPTURE_HOLD_EXPIRED, EXTENDED,
                EXTEND_AFTER_CAPTURE, EXTEND_BEYOND_LIFETIME, RELEASED, FX_RATES_NAD);
    }

    // ------------------------------------------------------------------ WireMock mappings

    public static ResponseDefinitionBuilder respond(Interaction interaction) {
        return aResponse().withStatus(interaction.status())
                .withHeader("Content-Type", interaction.contentType())
                .withBody(interaction.responseBody());
    }

    /** POST /api/v1/holds, matching the headers banking requires. */
    /** Keycloak's token endpoint, as logistics' OAuth2 client calls it for client credentials. */
    public static void stubTokenEndpoint() {
        BankingStub.SERVER.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"test-logistics-token\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                        + "\"scope\":\"banking.holds\"}")));
    }

    public static MappingBuilder createHold() {
        return post(urlPathEqualTo("/api/v1/holds"))
                .withHeader("Idempotency-Key", matching("shipment-.+-authorise"))
                .withHeader("Content-Type", matching("application/json.*"));
    }

    public static MappingBuilder capture() {
        return post(urlPathMatching("/api/v1/holds/[0-9a-f-]+/capture"))
                .withHeader("Idempotency-Key", matching("shipment-.+-capture"));
    }

    public static MappingBuilder extend() {
        return post(urlPathMatching("/api/v1/holds/[0-9a-f-]+/extension"))
                .withHeader("Idempotency-Key", matching("shipment-.+-extend-[0-9]+"))
                .withHeader("Content-Type", matching("application/json.*"));
    }

    public static MappingBuilder release() {
        return post(urlPathMatching("/api/v1/holds/[0-9a-f-]+/release"))
                .withHeader("Idempotency-Key", matching("shipment-.+-release"));
    }

    public static MappingBuilder holdFor(UUID shipmentId) {
        return post(urlPathEqualTo("/api/v1/holds"))
                .withHeader("Idempotency-Key", equalTo("shipment-" + shipmentId + "-authorise"));
    }
}
