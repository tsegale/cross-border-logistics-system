package com.freightflow.logistics.warmup;

import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.BankingStubs;
import com.freightflow.logistics.support.IntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real server on a random port, a key endpoint and banking's stub, so the warm-up runs as in production: it
 * fetches the signing keys, gets its own token and an FX rate from banking, queries every entity, prices a sample
 * quote it rolls back and sends its requests while the service still refuses traffic, and the service goes ready
 * afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"freightflow.warm-up.enabled=true", "freightflow.warm-up.rounds=2"})
class WarmUpIntegrationTest extends IntegrationTest {

    private static final RSAKey KEY = generate();
    private static final WireMockServer KEYCLOAK = new WireMockServer(wireMockConfig().dynamicPort());

    /** Runs as the context is created, so the stubs are there when the warm-up runs, before any test method. */
    @DynamicPropertySource
    static void keysAndBanking(DynamicPropertyRegistry registry) {
        if (!KEYCLOAK.isRunning()) {
            KEYCLOAK.start();
        }
        KEYCLOAK.stubFor(get(urlPathEqualTo("/certs")).willReturn(okJson(new JWKSet(KEY.toPublicJWK()).toString())));
        BankingStubs.stubTokenEndpoint();
        BankingStub.SERVER.stubFor(get(urlPathEqualTo("/api/v1/fx-rates")).willReturn(okJson("""
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

    @Autowired
    private ApplicationAvailability availability;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void warmsUpWhileRefusingTrafficThenGoesReadyWithKeysTokenAndBankingAlreadyReached() throws Exception {
        WarmUpRunner.Result result = warmUp.lastResult();

        assertThat(result).isNotNull();
        assertThat(result.readinessDuringWarmUp()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        assertThat(result.overHttp()).isTrue();
        // The keys and banking once, then per round the database, the sample quote and every path.
        assertThat(result.steps()).isEqualTo(2 + properties.rounds() * (2 + properties.paths().size()));
        assertThat(result.failed()).isZero();
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(meters.get("freightflow.warmup.duration").timer().count()).isEqualTo(1);

        // At least: the stub is shared by every cached context in this JVM, and theirs may call it too.
        BankingStub.SERVER.verify(moreThanOrExactly(1), postRequestedFor(urlPathEqualTo("/token")));
        // Once until banking answers, then once per sample quote for its indicative total.
        BankingStub.SERVER.verify(moreThanOrExactly(1 + properties.rounds()),
                getRequestedFor(urlPathEqualTo("/api/v1/fx-rates")));
        // Every sample quote was rolled back: none of their packages is in the database.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quote_packages WHERE description = ?", Long.class,
                WarmUpRunner.SAMPLE_MARKER)).isZero();

        // The first real token is checked against the keys the warm-up fetched: no key fetch of its own.
        int fetchesBeforeTheFirstToken = KEYCLOAK.findAll(getRequestedFor(urlPathEqualTo("/certs"))).size();
        assertThat(fetchesBeforeTheFirstToken).isPositive();
        assertThat(decoder.decode(signed()).getSubject()).isEqualTo("first-caller");
        assertThat(KEYCLOAK.findAll(getRequestedFor(urlPathEqualTo("/certs")))).hasSize(fetchesBeforeTheFirstToken);
    }

    private static String signed() throws JOSEException {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer("http://keycloak.test/realms/freightflow")
                        .audience(List.of("logistics-service")).subject("first-caller")
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("warm-up-test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
