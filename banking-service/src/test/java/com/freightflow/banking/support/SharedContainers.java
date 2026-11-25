package com.freightflow.banking.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * PostgreSQL 17 container started once per JVM and shared across every integration test class
 * (and cached Spring contexts) via {@code @ImportTestcontainers} and {@code @ServiceConnection}.
 */
public final class SharedContainers {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    @ServiceConnection
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
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        // Every internal TLS peer (the broker, KafkaBroker) is verified against this JVM's test CA.
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        registry.add("KAFKA_USERNAME", () -> "banking");
        registry.add("freightflow.kafka.password", () -> KAFKA.password("banking"));
    }

    private SharedContainers() {
    }
}
