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
import java.util.List;
import java.util.Map;

import static com.freightflow.gateway.support.TestIssuer.bearer;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Per-caller token buckets on money-moving routes (ADR 0017), against a real Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "RATE_LIMIT_MONEY_RPS=1",
        "RATE_LIMIT_MONEY_BURST=2"
})
class RateLimitTest {

    static final WireMockServer UPSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        UPSTREAM.start();
        UPSTREAM.stubFor(any(anyUrl()).willReturn(okJson("{}")));
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

    private WebTestClient.ResponseSpec transfer(String token) {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(10))
                .build().post().uri("/api/v1/transfers").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange();
    }

    @Test
    void aCallerOverItsBurstGetsA429ProblemWithRetryAfterAndRateLimitFields() {
        String token = TestIssuer.customer();
        transfer(token).expectStatus().isOk()
                .expectHeader().valueEquals("RateLimit-Policy", "\"money-banking\";q=2;w=2")
                .expectHeader().valueEquals("RateLimit", "\"money-banking\";r=1;t=1");
        transfer(token).expectStatus().isOk();
        transfer(token).expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectHeader().value("RateLimit", value -> assertThat(value).isEqualTo("\"money-banking\";r=0;t=2"))
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/rate-limited");
    }

    @Test
    void anotherCallerHasItsOwnBucket() {
        String first = TestIssuer.customer();
        String second = TestIssuer.token(Map.of("roles", List.of("customer")));
        transfer(first).expectStatus().isOk();
        transfer(first).expectStatus().isOk();
        transfer(first).expectStatus().isEqualTo(429);
        transfer(second).expectStatus().isOk();
    }
}
