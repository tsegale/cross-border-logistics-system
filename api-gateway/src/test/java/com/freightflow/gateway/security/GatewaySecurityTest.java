package com.freightflow.gateway.security;

import com.freightflow.gateway.support.TestIssuer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.freightflow.gateway.support.TestIssuer.bearer;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Authentication and the coarse role rules at the edge (ADR 0017), through the real security chain and routes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class GatewaySecurityTest {

    static final WireMockServer UPSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        UPSTREAM.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("BANKING_SERVICE_URI", UPSTREAM::baseUrl);
        registry.add("LOGISTICS_SERVICE_URI", UPSTREAM::baseUrl);
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop();
    }

    @LocalServerPort
    private int port;

    @AfterEach
    void reset() {
        UPSTREAM.resetAll();
    }

    private WebTestClient client() {
        UPSTREAM.stubFor(any(anyUrl()).willReturn(okJson("{}")));
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void noTokenIsA401ProblemWithABearerChallengeAndNeverReachesTheService() {
        client().get().uri("/api/v1/accounts/FF14EUR000000001").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/unauthenticated")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.requestId").exists();
        UPSTREAM.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void tokensThatAreNotExactlyRightAre401() {
        Instant now = Instant.now();
        Map<String, Object> customer = Map.of("roles", List.of("customer"));
        List<String> bad = List.of(
                "not-a-jwt",
                TestIssuer.sign(TestIssuer.OTHER_KEY, TestIssuer.base(customer).build()),           // unknown key
                TestIssuer.sign(TestIssuer.KEY, TestIssuer.base(customer).issuer("http://evil.test").build()),
                TestIssuer.sign(TestIssuer.KEY, TestIssuer.base(customer).audience("another-api").build()),
                TestIssuer.sign(TestIssuer.KEY, TestIssuer.base(customer)
                        .issueTime(Date.from(now.minusSeconds(900))).expirationTime(Date.from(now.minusSeconds(120)))
                        .build()),                                                                  // expired
                TestIssuer.signHs256(TestIssuer.base(customer).build()),                            // not RS256
                unsigned(TestIssuer.base(customer).build()));                                       // alg none
        for (String token : bad) {
            client().get().uri("/api/v1/accounts/FF14EUR000000001").header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, value -> assertThat(value)
                            .startsWith("Bearer").contains("invalid_token"))
                    .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/unauthenticated");
        }
        UPSTREAM.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource({
            "auditor,  POST, /api/v1/transfers,                        403",
            "auditor,  GET,  /api/v1/ledger/reconciliation,            200",
            "customer, GET,  /api/v1/ledger/reconciliation,            403",
            "customer, POST, /api/v1/accounts/FF14EUR000000001/deposits, 403",
            "operator, POST, /api/v1/accounts/FF14EUR000000001/deposits, 200",
            "customer, POST, /api/v1/journal-entries/x/reversals,       403",
            "carrier,  GET,  /api/v1/accounts/FF14EUR000000001,          403",
            "carrier,  POST, /api/v1/quotes,                            403",
            "carrier,  POST, /api/v1/shipments/x/tracking-events,       200",
            "customer, POST, /api/v1/shipments/x/tracking-events,       403",
            "carrier,  GET,  /api/v1/shipments/x,                       200",
            "customer, PUT,  /api/v1/shipments/x/carrier,               403",
            "operator, PUT,  /api/v1/shipments/x/carrier,               200",
            "customer, POST, /api/v1/payment-commands/x/retry,          403",
            "auditor,  POST, /api/v1/payment-commands/x/retry,          403",
            "auditor,  GET,  /api/v1/payment-commands,                  200",
            "operator, POST, /api/v1/payment-commands/x/retry,          200",
            "customer, GET,  /api/v1/accounts/FF14EUR000000001,          200",
    })
    void coarseRoleRulesAtTheEdge(String role, String method, String path, int expected) {
        client().method(HttpMethod.valueOf(method)).uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(TestIssuer.role(role)))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(expected);
        if (expected == 403) {
            UPSTREAM.verify(0, anyRequestedFor(anyUrl()));
        }
    }

    @Test
    void aRefusalIsA403Problem() {
        client().get().uri("/api/v1/ledger/reconciliation")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestIssuer.customer()))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/forbidden");
    }

    @Test
    void thePublicTrackingViewNeedsNoToken() {
        client().get().uri("/api/v1/shipments/track/FF-00010000-6").exchange().expectStatus().isOk();
        UPSTREAM.verify(getRequestedFor(urlPathEqualTo("/api/v1/shipments/track/FF-00010000-6")));
    }

    @Test
    void forgedIdentityHeadersAreStrippedAndTheTokenIsRelayedUnchanged() {
        String token = TestIssuer.customer();
        client().get().uri("/api/v1/accounts/FF14EUR000000001")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header("X-User-Id", "someone-else")
                .header("X-User-Roles", "operator")
                .header("X-Org-Id", "0193a0e0-0000-7000-8000-00000000a002")
                .header("X-Roles", "operator")
                .header("X-Forwarded-User", "operator")
                .exchange()
                .expectStatus().isOk();
        UPSTREAM.verify(getRequestedFor(urlPathEqualTo("/api/v1/accounts/FF14EUR000000001"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(bearer(token)))
                .withHeader("X-Request-Id", com.github.tomakehurst.wiremock.client.WireMock.matching(".+"))
                .withHeader("X-User-Id", absent())
                .withHeader("X-User-Roles", absent())
                .withHeader("X-Org-Id", absent())
                .withHeader("X-Roles", absent())
                .withHeader("X-Forwarded-User", absent()));
    }

    @Test
    void anInvalidTokenOnThePublicRouteIsStillA401() {
        client().get().uri("/api/v1/shipments/track/FF-00010000-6")
                .header(HttpHeaders.AUTHORIZATION, bearer("forged"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static String unsigned(JWTClaimsSet claims) {
        return new com.nimbusds.jwt.PlainJWT(claims).serialize();
    }
}
