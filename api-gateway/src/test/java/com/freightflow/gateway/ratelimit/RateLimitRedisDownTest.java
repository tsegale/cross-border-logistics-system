package com.freightflow.gateway.ratelimit;

import com.freightflow.gateway.support.TestIssuer;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static com.freightflow.gateway.support.TestIssuer.bearer;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * The rate limiter's Redis is unreachable (ADR 0017): money-moving routes fail closed with a retryable 503, everything
 * else is not rate limited and keeps working. Redis points at a port nothing listens on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class RateLimitRedisDownTest {

    static final WireMockServer UPSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        UPSTREAM.start();
        UPSTREAM.stubFor(any(anyUrl()).willReturn(okJson("{}")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("REDIS_HOST", () -> "127.0.0.1");
        registry.add("REDIS_PORT", () -> 1);
        registry.add("BANKING_SERVICE_URI", UPSTREAM::baseUrl);
        registry.add("LOGISTICS_SERVICE_URI", UPSTREAM::baseUrl);
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop();
    }

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(10))
                .defaultHeader(HttpHeaders.AUTHORIZATION, bearer(TestIssuer.customer())).build();
    }

    @Test
    void moneyMovingRoutesFailClosed() {
        for (String path : new String[] {"/api/v1/transfers", "/api/v1/shipments", "/api/v1/fx-quotes"}) {
            client().post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                    .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/rate-limiter-unavailable");
        }
        UPSTREAM.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void readsAreNotRateLimitedAndKeepWorking() {
        client().get().uri("/api/v1/accounts/FF14EUR000000001").exchange().expectStatus().isOk();
        UPSTREAM.verify(anyRequestedFor(urlPathEqualTo("/api/v1/accounts/FF14EUR000000001")));
    }
}
