package com.freightflow.banking.warmup;

import com.freightflow.banking.support.IntegrationTest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real server on a random port and a key endpoint of its own, so the warm-up runs as in production: it fetches the
 * signing keys, queries every entity and sends its requests while the service still refuses traffic, and the service
 * goes ready afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"freightflow.warm-up.enabled=true", "freightflow.warm-up.rounds=2",
                "freightflow.banking.fx.reference.provider=static"})
class WarmUpIntegrationTest extends IntegrationTest {

    private static final RSAKey KEY = generate();
    private static final AtomicInteger KEY_FETCHES = new AtomicInteger();
    private static final HttpServer KEYCLOAK = keyEndpoint();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("freightflow.security.jwk-set-uri",
                () -> "http://127.0.0.1:" + KEYCLOAK.getAddress().getPort() + "/certs");
    }

    @AfterAll
    static void stop() {
        KEYCLOAK.stop(0);
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

    @Test
    void warmsUpWhileRefusingTrafficThenGoesReadyWithTheKeysAlreadyFetched() throws Exception {
        WarmUpRunner.Result result = warmUp.lastResult();

        assertThat(result).isNotNull();
        assertThat(result.readinessDuringWarmUp()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        assertThat(result.overHttp()).isTrue();
        // The keys once, then per round the database and every path.
        assertThat(result.steps()).isEqualTo(1 + properties.rounds() * (1 + properties.paths().size()));
        assertThat(result.failed()).isZero();
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(meters.get("freightflow.warmup.duration").timer().count()).isEqualTo(1);

        // The first real token is checked against the keys the warm-up fetched: no key fetch of its own.
        int fetchesBeforeTheFirstToken = KEY_FETCHES.get();
        assertThat(fetchesBeforeTheFirstToken).isPositive();
        assertThat(decoder.decode(signed()).getSubject()).isEqualTo("first-caller");
        assertThat(KEY_FETCHES.get()).isEqualTo(fetchesBeforeTheFirstToken);
    }

    private static String signed() throws JOSEException {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer("http://keycloak.test/realms/freightflow")
                        .audience(List.of("banking-service")).subject("first-caller")
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

    private static HttpServer keyEndpoint() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                KEY_FETCHES.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
