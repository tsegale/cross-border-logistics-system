package com.freightflow.gateway.ratelimit;

import com.freightflow.gateway.support.TestIssuer;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Carriers' notifications come without a token (ADR 0037), so they are budgeted per carrier in the path and per
 * source address instead, and their bodies are capped tighter than the rest (ADR 0041). Against a real Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "RATE_LIMIT_WEBHOOK_CARRIER_RPS=1",
        "RATE_LIMIT_WEBHOOK_CARRIER_BURST=3",
        "RATE_LIMIT_WEBHOOK_SOURCE_RPS=1",
        "RATE_LIMIT_WEBHOOK_SOURCE_BURST=5"
})
class CarrierWebhookLimitsTest {

    static final WireMockServer LOGISTICS = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        LOGISTICS.start();
        LOGISTICS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(204)));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("LOGISTICS_SERVICE_URI", () -> TestIssuer.url(LOGISTICS));
    }

    @AfterAll
    static void stop() {
        LOGISTICS.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meters;

    private WebTestClient.ResponseSpec push(String carrier, String body) {
        return TestIssuer.gateway(port).responseTimeout(Duration.ofSeconds(10)).build()
                .post().uri("/api/v1/dcsa/notifications/" + carrier)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    @Test
    void aCarrierAndASourceEachHaveTheirOwnBudget() throws InterruptedException {
        // A cold context's first call (Redis, the Lua script, the upstream pool); then the buckets fill again.
        push("warm-up", "[]").expectStatus().isNoContent();
        Thread.sleep(6_000);
        startOfASecond();

        for (int i = 0; i < 3; i++) {
            push("carrier-a", "[]").expectStatus().isNoContent();
        }
        push("carrier-a", "[]").expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/rate-limited");
        // Another carrier still has its budget, until the source's runs out: three and two make five.
        push("carrier-b", "[]").expectStatus().isNoContent();
        push("carrier-b", "[]").expectStatus().isNoContent();
        push("carrier-b", "[]").expectStatus().isEqualTo(429);

        assertThat(meters.get(RateLimitGatewayFilterFactory.REFUSED).tag("route", "carrier-webhooks")
                .tag("key", "carrier").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get(RateLimitGatewayFilterFactory.REFUSED).tag("route", "carrier-webhooks")
                .tag("key", "source").counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aNotificationOverTheWebhookLimitIsRefusedBeforeItIsRead() {
        String big = "[" + "\"x\",".repeat(70_000) + "\"x\"]";
        assertThat(big.length()).isGreaterThan(256 * 1024);
        push("carrier-c", big).expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.type").isEqualTo("https://freightflow.dev/problems/request-too-large");
    }

    private static void startOfASecond() throws InterruptedException {
        long intoTheSecond = System.currentTimeMillis() % 1000;
        if (intoTheSecond > 100) {
            Thread.sleep(1000 - intoTheSecond);
        }
    }
}
