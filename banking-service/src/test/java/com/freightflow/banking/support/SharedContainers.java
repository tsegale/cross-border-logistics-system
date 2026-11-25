package com.freightflow.banking.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * PostgreSQL 17 container started once per JVM and shared across every integration test class
 * (and cached Spring contexts) via {@code @ImportTestcontainers}.
 */
public final class SharedContainers {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine"))
            // Several cached Spring contexts (one per posting mode, ADR 0010) each keep a pool open.
            .withCommand("postgres", "-c", "max_connections=400")
            .withStartupTimeout(STARTUP_TIMEOUT);

    /** The broker as compose runs it, SCRAM and ACLs included (ADR 0022); banking logs in as its own user. */
    public static final KafkaBroker KAFKA = new KafkaBroker();

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        database(registry);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        // Every internal TLS peer (the broker, KafkaBroker) is verified against this JVM's test CA.
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        // This service's identity from the test CA: its bundle holds a certificate wherever it runs (ADR 0040).
        registry.add("spring.ssl.bundle.pem.internal.keystore.certificate",
                () -> TestCertificates.of("banking-service").certificatePem());
        registry.add("spring.ssl.bundle.pem.internal.keystore.private-key",
                () -> TestCertificates.of("banking-service").privateKeyPem());
        // Boot reloads only files; these are PEM content.
        registry.add("spring.ssl.bundle.pem.internal.reload-on-update", () -> "false");
        registry.add("KAFKA_USERNAME", () -> "banking");
        registry.add("freightflow.kafka.password", () -> KAFKA.password("banking"));
    }

    /** The runtime role's password in the tests; the container's own superuser is the owner that migrates. */
    public static final String APP_PASSWORD = "banking-app-test-password";

    /**
     * The service connects as its runtime role, banking_app, and Flyway migrates as the owner, as in compose (ADR 0041):
     * a grant the code needs and the migration forgot fails a test, not production. Not a service connection, which
     * would log the service in as the container's superuser.
     */
    public static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app-password", () -> APP_PASSWORD);
        registry.add("spring.flyway.placeholders.monitoring-password", () -> "");
    }

    /** The owner's connection, for what the runtime role may not do: a test's setup, or a check of a trigger. */
    public static org.springframework.jdbc.core.JdbcTemplate owner() {
        return new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private SharedContainers() {
    }
}
