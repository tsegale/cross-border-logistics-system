package com.freightflow.logistics.warmup;

import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.IntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cold start of the whole stack: banking answers 503 for its first three calls, the way a service still starting
 * does. The warm-up waits for it on its own deadline, and its budget (1 s here) starts only after, so every round
 * still runs. Before, the wait came out of the budget and the warm-up stopped before its first round on the laptop.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "freightflow.warm-up.enabled=true", "freightflow.warm-up.rounds=2", "freightflow.warm-up.timeout=1s",
        "freightflow.warm-up.dependency-timeout=30s"})
class WarmUpColdStartIntegrationTest extends IntegrationTest {

    private static final WireMockServer KEYCLOAK = new WireMockServer(wireMockConfig().dynamicPort());
    private static final String COLD = "banking still starting";

    @DynamicPropertySource
    static void keysAndAColdBanking(DynamicPropertyRegistry registry) throws JOSEException {
        if (!KEYCLOAK.isRunning()) {
            KEYCLOAK.start();
        }
        KEYCLOAK.stubFor(get(urlPathEqualTo("/certs")).willReturn(okJson(
                new JWKSet(new RSAKeyGenerator(2048).keyID("cold-start").generate().toPublicJWK()).toString())));
        BankingStubs.stubTokenEndpoint();
        String state = Scenario.STARTED;
        for (int call = 1; call <= 3; call++) {
            BankingStub.SERVER.stubFor(get(urlPathEqualTo("/api/v1/fx-rates")).inScenario(COLD)
                    .whenScenarioStateIs(state).willReturn(aResponse().withStatus(503))
                    .willSetStateTo("call " + call));
            state = "call " + call;
        }
        BankingStub.SERVER.stubFor(get(urlPathEqualTo("/api/v1/fx-rates")).inScenario(COLD)
                .whenScenarioStateIs(state).willReturn(okJson("""
                        {"base":"EUR","asOf":"2026-11-10T09:00:00Z","source":"static-configuration","rates":[]}
                        """)));
        registry.add("freightflow.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYCLOAK.port() + "/certs");
    }

    @AfterAll
    static void stop() {
        KEYCLOAK.stop();
    }

    @Autowired
    private WarmUpRunner warmUp;

    @Autowired
    private WarmUpProperties properties;

    @Test
    void waitingForBankingDoesNotUseTheWarmUpsOwnBudget() {
        WarmUpRunner.Result result = warmUp.lastResult();

        assertThat(result).isNotNull();
        assertThat(result.millis()).as("it waited for banking, longer than its own 1 s budget").isGreaterThan(1000);
        // Banking, the keys, then every round: none was cut short by the time spent waiting.
        assertThat(result.steps()).isEqualTo(2 + properties.rounds() * (2 + properties.paths().size()));
    }
}
