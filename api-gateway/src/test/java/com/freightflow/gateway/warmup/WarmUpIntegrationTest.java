package com.freightflow.gateway.warmup;

import com.freightflow.gateway.support.TestIssuer;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real gateway on a random port with Redis, a key endpoint and both services stubbed, so the warm-up runs as in
 * production: keys, Redis, a connection to each service and requests to its own port, all while the gateway still
 * refuses traffic, and the gateway goes ready afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "freightflow.warm-up.enabled=true",
        "freightflow.warm-up.rounds=2",
        "management.server.port=0"
})
class WarmUpIntegrationTest {

    static final WireMockServer BANKING = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer LOGISTICS = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer NOTIFICATION = new WireMockServer(wireMockConfig().dynamicPort());
    /** Keycloak behind the gateway (ADR 0041): its connection is warmed like a service's. */
    static final WireMockServer KEYCLOAK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        BANKING.start();
        LOGISTICS.start();
        NOTIFICATION.start();
        KEYCLOAK.start();
        BANKING.stubFor(get(anyUrl()).willReturn(okJson("{\"status\":\"UP\"}")));
        LOGISTICS.stubFor(get(anyUrl()).willReturn(okJson("{\"status\":\"UP\"}")));
        NOTIFICATION.stubFor(get(anyUrl()).willReturn(okJson("{\"status\":\"UP\"}")));
        KEYCLOAK.stubFor(get(anyUrl()).willReturn(okJson("{\"status\":\"UP\"}")));
        TestIssuer.serveKeys(TestIssuer.KEY);
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("BANKING_SERVICE_URI", () -> TestIssuer.url(BANKING));
        registry.add("LOGISTICS_SERVICE_URI", () -> TestIssuer.url(LOGISTICS));
        registry.add("NOTIFICATION_SERVICE_URI", () -> TestIssuer.url(NOTIFICATION));
        registry.add("KEYCLOAK_SERVICE_URI", () -> TestIssuer.url(KEYCLOAK));
    }

    @AfterAll
    static void stop() {
        BANKING.stop();
        LOGISTICS.stop();
        NOTIFICATION.stop();
        KEYCLOAK.stop();
    }

    @Autowired
    private WarmUpRunner warmUp;

    @Autowired
    private WarmUpProperties properties;

    @Autowired
    private ApplicationAvailability availability;

    @Autowired
    private ReactiveJwtDecoder decoder;

    @Autowired
    private MeterRegistry meters;

    @Test
    void warmsUpWhileRefusingTrafficThenGoesReadyWithKeysRedisAndEveryServiceReached() {
        WarmUpRunner.Result result = warmUp.lastResult();

        assertThat(result).isNotNull();
        assertThat(result.readinessDuringWarmUp()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        assertThat(result.overHttp()).isTrue();
        assertThat(result.upstreams()).isEqualTo(4);
        // Keys and Redis once, then per round each service and every path.
        assertThat(result.steps()).isEqualTo(2 + properties.rounds() * (4 + properties.paths().size()));
        assertThat(result.failed()).isZero();
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(meters.get("freightflow.warmup.duration").timer().count()).isEqualTo(1);

        // Each service saw only its readiness probe: the warm-up never sends an API request downstream.
        for (WireMockServer service : new WireMockServer[] {BANKING, LOGISTICS, NOTIFICATION, KEYCLOAK}) {
            assertThat(service.getAllServeEvents()).hasSize(properties.rounds());
            service.verify(properties.rounds(), getRequestedFor(urlPathEqualTo("/actuator/health/readiness")));
        }

        // The first real token is checked against the keys the warm-up fetched: no key fetch of its own.
        int fetchesBeforeTheFirstToken = TestIssuer.JWKS.findAll(getRequestedFor(urlPathEqualTo("/certs"))).size();
        assertThat(fetchesBeforeTheFirstToken).isPositive();
        assertThat(decoder.decode(TestIssuer.customer()).block(Duration.ofSeconds(5))).isNotNull();
        assertThat(TestIssuer.JWKS.findAll(getRequestedFor(urlPathEqualTo("/certs")))).hasSize(fetchesBeforeTheFirstToken);
    }
}
