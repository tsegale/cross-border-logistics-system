package com.freightflow.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.freightflow.gateway.support.TestIssuer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/** The edge policy (ADR 0015) against WireMock upstreams standing in for banking and logistics. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s",
        "freightflow.gateway.max-request-size=1KB",
        "management.server.port=0"
})
class GatewayEdgeTest {

    static final WireMockServer BANKING = new WireMockServer(wireMockConfig().dynamicPort());
    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    static {
        BANKING.start();
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("BANKING_SERVICE_URI", BANKING::baseUrl);
        // Nothing listens on port 1: the logistics route stands for a service that is down.
        registry.add("LOGISTICS_SERVICE_URI", () -> "http://127.0.0.1:1");
    }

    @AfterAll
    static void stop() {
        BANKING.stop();
    }

    @LocalServerPort
    private int port;

    @AfterEach
    void reset() {
        BANKING.resetAll();
    }

    /** An operator: allowed every route at the edge, so these tests exercise the edge policy, not the roles. */
    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, TestIssuer.bearer(TestIssuer.role("operator")))
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void routesToTheServiceAndPassesItsAnswerThrough() {
        BANKING.stubFor(get(urlPathEqualTo("/api/v1/accounts/FF87NAD000000001"))
                .willReturn(okJson("{\"accountNumber\":\"FF87NAD000000001\"}")));

        client().get().uri("/api/v1/accounts/FF87NAD000000001").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.accountNumber").isEqualTo("FF87NAD000000001");
    }

    @Test
    void serviceProblemDetailsPassThroughUnchanged() {
        BANKING.stubFor(get(urlPathEqualTo("/api/v1/accounts/FF00NAD000001000")).willReturn(aResponse()
                .withStatus(400).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"https://freightflow.dev/problems/invalid-account-number\",\"status\":400}")));

        client().get().uri("/api/v1/accounts/FF00NAD000001000").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/invalid-account-number");
    }

    @Test
    void slowUpstreamIsA504Problem() {
        BANKING.stubFor(get(urlPathEqualTo("/api/v1/ledger/reconciliation"))
                .willReturn(okJson("{}").withFixedDelay(3_000)));

        client().get().uri("/api/v1/ledger/reconciliation").exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/upstream-timeout");
    }

    @Test
    void upstreamThatIsDownIsA503Problem() {
        client().get().uri("/api/v1/quotes/01950000-0000-7000-8000-000000000001").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/upstream-unavailable");
    }

    @Test
    void internalPathsAre404AtTheEdgeAndNeverReachTheService() {
        BANKING.stubFor(post(anyUrl()).willReturn(okJson("{}")));
        BANKING.stubFor(get(anyUrl()).willReturn(okJson("{}")));

        for (String path : new String[] {"/api/v1/holds", "/api/v1/holds/01a1c900-0000-7000-8000-000000000001/capture",
                "/actuator/health", "/actuator/prometheus", "/API/V1/HOLDS"}) {
            client().post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/not-found");
        }
        BANKING.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void unknownPathIsA404Problem() {
        client().get().uri("/nowhere").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/not-found");
    }

    @Test
    void requestIdIsGeneratedForwardedAndReturned() {
        BANKING.stubFor(get(urlPathEqualTo("/api/v1/fx-rates")).willReturn(okJson("{}")));

        client().get().uri("/api/v1/fx-rates?base=EUR").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("X-Request-Id", UUID_PATTERN);
        BANKING.verify(getRequestedFor(urlPathEqualTo("/api/v1/fx-rates")).withHeader("X-Request-Id", matching(UUID_PATTERN)));
    }

    @Test
    void validClientRequestIdIsKeptAndAnInvalidOneReplaced() {
        BANKING.stubFor(get(urlPathEqualTo("/api/v1/fx-rates")).willReturn(okJson("{}")));

        client().get().uri("/api/v1/fx-rates?base=EUR").header("X-Request-Id", "client-trace.42").exchange()
                .expectHeader().valueEquals("X-Request-Id", "client-trace.42");
        BANKING.verify(getRequestedFor(urlPathEqualTo("/api/v1/fx-rates")).withHeader("X-Request-Id", equalTo("client-trace.42")));

        client().get().uri("/api/v1/fx-rates?base=EUR").header("X-Request-Id", "bad id with spaces").exchange()
                .expectHeader().valueMatches("X-Request-Id", UUID_PATTERN);
    }

    @Test
    void bodyOverTheLimitIsA413Problem() {
        client().post().uri("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).bodyValue("x".repeat(2_048))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/request-too-large");
        BANKING.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void idempotentReadIsRetriedOnA503() {
        BANKING.stubFor(get(urlPathEqualTo("/api/v1/journal-entries/x")).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        BANKING.stubFor(get(urlPathEqualTo("/api/v1/journal-entries/x")).inScenario("flaky")
                .whenScenarioStateIs("recovered").willReturn(okJson("{\"id\":\"x\"}")));

        client().get().uri("/api/v1/journal-entries/x").exchange()
                .expectStatus().isOk();
        BANKING.verify(2, getRequestedFor(urlPathEqualTo("/api/v1/journal-entries/x")));
    }

    @Test
    void postIsNeverRetriedAtTheEdge() {
        BANKING.stubFor(post(urlPathEqualTo("/api/v1/transfers")).willReturn(aResponse().withStatus(503)));

        client().post().uri("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k-1").bodyValue("{}").exchange()
                .expectStatus().isEqualTo(503);
        BANKING.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/transfers")));
    }
}
