package com.freightflow.logistics.support;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SharedContainers {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine"))
            .withStartupTimeout(STARTUP_TIMEOUT);

    /** The broker as compose runs it, SCRAM and ACLs included (ADR 0022); logistics logs in as its own user. */
    public static final KafkaBroker KAFKA = new KafkaBroker();

    /** banking declares its topics in production; here logistics' consumer needs the one it reads to exist. */
    public static final String BANKING_HOLD_TOPIC = "freightflow.hold.v1";

    static {
        POSTGRES.start();
        KAFKA.start();
        try (Admin admin = Admin.create(KAFKA.clientConfig("admin"))) {
            admin.createTopics(List.of(new NewTopic(BANKING_HOLD_TOPIC, 6, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("Could not create " + BANKING_HOLD_TOPIC, e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Could not create " + BANKING_HOLD_TOPIC, e);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        database(registry);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        // Every internal TLS peer (the broker, KafkaBroker) is verified against this JVM's test CA.
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        // This service's identity from the test CA: its bundle holds a certificate wherever it runs (ADR 0040).
        registry.add("spring.ssl.bundle.pem.internal.keystore.certificate",
                () -> TestCertificates.of("logistics-service").certificatePem());
        registry.add("spring.ssl.bundle.pem.internal.keystore.private-key",
                () -> TestCertificates.of("logistics-service").privateKeyPem());
        // Boot reloads only files; these are PEM content.
        registry.add("spring.ssl.bundle.pem.internal.reload-on-update", () -> "false");
        registry.add("KAFKA_USERNAME", () -> "logistics");
        registry.add("freightflow.kafka.password", () -> KAFKA.password("logistics"));
    }

    /** The runtime role's password in the tests; the container's own superuser is the owner that migrates. */
    public static final String APP_PASSWORD = "logistics-app-test-password";

    /**
     * The service connects as its runtime role, logistics_app, and Flyway migrates as the owner, as in compose (ADR 0041):
     * a grant the code needs and the migration forgot fails a test, not production. Not a service connection, which
     * would log the service in as the container's superuser.
     */
    public static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "logistics_app");
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
