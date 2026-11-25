package com.freightflow.notification.support;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Postgres, the broker as compose runs it (SCRAM and ACLs, ADR 0022) and Mailpit, started once per JVM. The topics
 * banking and logistics declare in production are created here as admin, so the consumers have them to read.
 */
public final class SharedContainers {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
    public static final String SMTP_USER = "freightflow";
    public static final String SMTP_PASSWORD = "test-only-smtp-password";

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine"))
            .withStartupTimeout(STARTUP_TIMEOUT);

    public static final KafkaBroker KAFKA = new KafkaBroker();

    /**
     * An SMTP server with an HTTP API, the one compose runs (ADR 0027): SMTP auth inside STARTTLS, which it requires,
     * with a certificate from the test CA (ADR 0040). The API stays plain HTTP here; only the tests read it.
     */
    public static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse(Mailpit.IMAGE))
            .withExposedPorts(1025, 8025)
            .withEnv("MP_SMTP_AUTH", SMTP_USER + ":" + SMTP_PASSWORD)
            .withCopyToContainer(Transferable.of(TestCertificates.of("mailpit").certificatePem(), 0644), "/tls/cert.pem")
            .withCopyToContainer(Transferable.of(TestCertificates.of("mailpit").privateKeyPem(), 0644), "/tls/key.pem")
            .withEnv("MP_SMTP_TLS_CERT", "/tls/cert.pem")
            .withEnv("MP_SMTP_TLS_KEY", "/tls/key.pem")
            .withEnv("MP_SMTP_REQUIRE_STARTTLS", "true")
            .waitingFor(Wait.forHttp("/readyz").forPort(8025))
            .withStartupTimeout(STARTUP_TIMEOUT);

    static {
        POSTGRES.start();
        KAFKA.start();
        MAILPIT.start();
        try (Admin admin = Admin.create(KAFKA.clientConfig("admin"))) {
            admin.createTopics(List.of(new NewTopic("freightflow.shipment.v1", 6, (short) 1),
                    new NewTopic("freightflow.hold.v1", 6, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("Could not create the consumed topics", e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Could not create the consumed topics", e);
        }
    }

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        database(registry);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        // Every internal TLS peer (the broker, KafkaBroker) is verified against this JVM's test CA.
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        // This service's identity from the test CA: its bundle holds a certificate wherever it runs (ADR 0040).
        registry.add("spring.ssl.bundle.pem.internal.keystore.certificate",
                () -> TestCertificates.of("notification-service").certificatePem());
        registry.add("spring.ssl.bundle.pem.internal.keystore.private-key",
                () -> TestCertificates.of("notification-service").privateKeyPem());
        // Boot reloads only files; these are PEM content.
        registry.add("spring.ssl.bundle.pem.internal.reload-on-update", () -> "false");
        registry.add("KAFKA_USERNAME", () -> "notification");
        registry.add("freightflow.kafka.password", () -> KAFKA.password("notification"));
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("spring.mail.username", () -> SMTP_USER);
        registry.add("spring.mail.password", () -> SMTP_PASSWORD);
        // STARTTLS required, as in compose: the server's certificate checked against the test CA, by name.
        registry.add("SMTP_STARTTLS", () -> "true");
    }

    public static String mailpitApi() {
        return "http://127.0.0.1:" + MAILPIT.getMappedPort(8025);
    }

    /** The runtime role's password in the tests; the container's own superuser is the owner that migrates. */
    public static final String APP_PASSWORD = "notification-app-test-password";

    /**
     * The service connects as its runtime role, notification_app, and Flyway migrates as the owner, as in compose (ADR 0041):
     * a grant the code needs and the migration forgot fails a test, not production. Not a service connection, which
     * would log the service in as the container's superuser.
     */
    public static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "notification_app");
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
