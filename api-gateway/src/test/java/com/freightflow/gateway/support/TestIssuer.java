package com.freightflow.gateway.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Stands in for Keycloak in gateway tests: an RSA key of its own, its public half served as a JWKS by WireMock, and
 * tokens shaped like the realm's (ADR 0016). A real Redis backs the rate limiter. The realm itself is tested against a
 * real Keycloak in KeycloakRealmIntegrationTest.
 */
public final class TestIssuer {

    public static final String ISSUER = "http://keycloak.test/realms/freightflow";
    public static final String NAMIB = "0193a0e0-0000-7000-8000-00000000a001";
    public static final RSAKey KEY = generate("test-key-1");
    public static final RSAKey OTHER_KEY = generate("test-key-2");
    public static final WireMockServer JWKS = new WireMockServer(wireMockConfig().dynamicPort());
    @SuppressWarnings("resource") // one Redis for the whole test JVM, stopped by Testcontainers' reaper
    public static final GenericContainer<?> REDIS = redis();

    static {
        JWKS.start();
        serveKeys(KEY);
        REDIS.start();
    }

    private TestIssuer() {
    }

    /**
     * Redis as compose runs it: {@code infra/redis/start.sh}, TLS only, as the image's own user, with a certificate
     * from this JVM's test CA in the agent's layout (ADR 0039).
     */
    private static GenericContainer<?> redis() {
        TestCertificates.Identity redis = TestCertificates.issue("redis");
        return new GenericContainer<>(DockerImageName.parse(composeImage("redis")))
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "infra", "redis").toAbsolutePath()
                        .normalize()), "/etc/freightflow/redis")
                .withCopyToContainer(Transferable.of(redis.certificatePem(), 0644), "/tls/cert.pem")
                .withCopyToContainer(Transferable.of(redis.privateKeyPem(), 0644), "/tls/key.pem")
                .withCopyToContainer(Transferable.of(TestCertificates.caPem(), 0644), "/tls/ca.pem")
                .withCreateContainerCmdModifier(cmd -> cmd.withUser("999:1000")
                        .withEntrypoint("sh", "/etc/freightflow/redis/start.sh"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
    }

    /**
     * The image a service of docker-compose.yml runs, so the tests run the rate-limit script on the Redis the stack
     * runs; a version bump there moves the tests with it.
     */
    static String composeImage(String service) {
        Path compose = Path.of("..", "docker-compose.yml");
        try {
            List<String> lines = Files.readAllLines(compose);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).equals("  " + service + ":")) {
                    for (int j = i + 1; j < lines.size() && lines.get(j).startsWith("    "); j++) {
                        String line = lines.get(j).trim();
                        if (line.startsWith("image: ")) {
                            return line.substring("image: ".length()).trim();
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + compose.toAbsolutePath(), ex);
        }
        throw new IllegalStateException("no image for " + service + " in " + compose.toAbsolutePath());
    }

    public static void serveKeys(RSAKey... keys) {
        JWKS.stubFor(get(urlPathEqualTo("/certs"))
                .willReturn(okJson(new JWKSet(List.of(keys)).toPublicJWKSet().toString())));
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("KEYCLOAK_ISSUER_URI", () -> ISSUER);
        registry.add("KEYCLOAK_JWK_SET_URI", () -> url(JWKS) + "/certs");
        // The first key fetch of a context can be slow on a loaded machine; production keeps the 3 s default.
        registry.add("freightflow.security.jwks-timeout", () -> "10s");
        // "localhost" resolves to ::1 first on Windows, and the first handshake then misses the 1 s timeout.
        registry.add("REDIS_HOST", () -> "localhost".equals(REDIS.getHost()) ? "127.0.0.1" : REDIS.getHost());
        registry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
        // Redis's TLS, verified against this JVM's test CA.
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        // The JVM's first Redis connection also loads Lettuce and Netty; production keeps the 1 s default.
        registry.add("REDIS_TIMEOUT", () -> "5s");
        // On only in WarmUpIntegrationTest: elsewhere its requests would land in the upstream stubs' journals.
        registry.add("WARM_UP_ENABLED", () -> "false");
    }

    public static String customer() {
        return token(Map.of("roles", List.of("customer"),
                "organization", Map.of("namib-exports", Map.of("id", NAMIB))));
    }

    public static String role(String role) {
        return token(Map.of("roles", List.of(role)));
    }

    public static String token(Map<String, Object> claims) {
        return sign(KEY, base(claims).build());
    }

    public static JWTClaimsSet.Builder base(Map<String, Object> claims) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .audience(List.of("api-gateway", "banking-service", "logistics-service"))
                .claim("azp", "freightflow-web")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString());
        claims.forEach(builder::claim);
        return builder;
    }

    public static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                    .type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** HS256 with a secret: must never be accepted, whatever it claims. */
    public static String signHs256(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new MACSigner("a-secret-that-is-at-least-256-bits-long-for-hs256!"));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * A WireMock server's address as 127.0.0.1. Its own {@code baseUrl()} says "localhost", which tries ::1 first on
     * Windows and costs every new connection a refused attempt.
     */
    public static String url(WireMockServer server) {
        return "http://127.0.0.1:" + server.port();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
