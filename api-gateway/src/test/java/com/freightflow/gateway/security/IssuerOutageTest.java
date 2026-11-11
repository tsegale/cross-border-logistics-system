package com.freightflow.gateway.security;

import com.freightflow.gateway.support.TestIssuer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.freightflow.gateway.support.TestIssuer.bearer;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Keycloak's key endpoint goes down (ADR 0017): tokens signed with a key the gateway already has keep working; a
 * token with an unknown key cannot be checked, which is a retryable 503, not a 401. Unknown key ids refetch the keys
 * at most once per jwks-min-refresh, so forged key ids cannot flood Keycloak.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "freightflow.security.jwks-min-refresh=2s"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IssuerOutageTest {

    static final WireMockServer KEYS = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer UPSTREAM = new WireMockServer(wireMockConfig().dynamicPort());
    static final RSAKey ROTATED = rotated();

    static {
        KEYS.start();
        UPSTREAM.start();
        KEYS.stubFor(get(urlPathEqualTo("/certs")).willReturn(okJson(new JWKSet(TestIssuer.KEY).toPublicJWKSet().toString())));
        UPSTREAM.stubFor(any(anyUrl()).willReturn(okJson("{}")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("KEYCLOAK_JWK_SET_URI", () -> KEYS.baseUrl() + "/certs");
        registry.add("BANKING_SERVICE_URI", UPSTREAM::baseUrl);
        registry.add("LOGISTICS_SERVICE_URI", UPSTREAM::baseUrl);
    }

    @AfterAll
    static void stop() {
        KEYS.stop();
        UPSTREAM.stop();
    }

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(15)).build();
    }

    @Test
    @Order(1)
    void aKnownKeyIsFetchedOnceAndThenCached() {
        for (int i = 0; i < 3; i++) {
            client().get().uri("/api/v1/fx-rates?base=EUR").header(HttpHeaders.AUTHORIZATION, bearer(TestIssuer.customer()))
                    .exchange().expectStatus().isOk();
        }
        KEYS.verify(1, getRequestedFor(urlPathEqualTo("/certs")));
    }

    @Test
    @Order(2)
    void withKeycloakDownCachedKeysKeepWorkingAndAnUnknownKeyIsARetryable503() throws InterruptedException {
        KEYS.resetAll();
        Thread.sleep(2_100); // past the minimum refresh interval, so an unknown key id may fetch again
        KEYS.stubFor(get(urlPathEqualTo("/certs")).willReturn(aResponse().withStatus(503)));

        client().get().uri("/api/v1/fx-rates?base=EUR").header(HttpHeaders.AUTHORIZATION, bearer(TestIssuer.customer()))
                .exchange().expectStatus().isOk();

        String rotatedToken = TestIssuer.sign(ROTATED, TestIssuer.base(Map.of("roles", List.of("customer"))).build());
        client().get().uri("/api/v1/fx-rates?base=EUR").header(HttpHeaders.AUTHORIZATION, bearer(rotatedToken))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/issuer-unavailable");
    }

    @Test
    @Order(3)
    void unknownKeyIdsDoNotRefetchTheKeysAgainWithinTheMinimumInterval() throws InterruptedException {
        KEYS.resetAll();
        KEYS.stubFor(get(urlPathEqualTo("/certs")).willReturn(okJson(new JWKSet(TestIssuer.KEY).toPublicJWKSet().toString())));
        Thread.sleep(2_100);
        for (int i = 0; i < 5; i++) {
            String forged = TestIssuer.sign(rotated(), TestIssuer.base(Map.of("roles", List.of("customer"))).build());
            client().get().uri("/api/v1/fx-rates?base=EUR").header(HttpHeaders.AUTHORIZATION, bearer(forged))
                    .exchange().expectStatus().isUnauthorized();
        }
        // Keycloak is back: the first forged key id fetches once and finds nothing (401); the other four, inside the
        // 2 s interval, fetch nothing at all.
        KEYS.verify(1, getRequestedFor(urlPathEqualTo("/certs")));
    }

    private static RSAKey rotated() {
        try {
            return new RSAKeyGenerator(2048).keyID("rotated-" + System.nanoTime()).generate();
        } catch (com.nimbusds.jose.JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
