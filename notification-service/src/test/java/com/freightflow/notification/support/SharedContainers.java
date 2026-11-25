package com.freightflow.notification.support;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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

    @ServiceConnection
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine"))
            .withStartupTimeout(STARTUP_TIMEOUT);

    public static final KafkaBroker KAFKA = new KafkaBroker();

    /** An SMTP server with an HTTP API, the one compose runs (ADR 0027); SMTP auth as in compose. */
    public static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse(Mailpit.IMAGE))
            .withExposedPorts(1025, 8025)
            .withEnv("MP_SMTP_AUTH", SMTP_USER + ":" + SMTP_PASSWORD)
            .withEnv("MP_SMTP_AUTH_ALLOW_INSECURE", "true")
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
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        // The broker's TLS listener, verified against this JVM's test CA (KafkaBroker).
        registry.add("spring.ssl.bundle.pem.kafka.truststore.certificate", TestCertificates::caPem);
        registry.add("KAFKA_USERNAME", () -> "notification");
        registry.add("freightflow.kafka.password", () -> KAFKA.password("notification"));
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("spring.mail.username", () -> SMTP_USER);
        registry.add("spring.mail.password", () -> SMTP_PASSWORD);
    }

    public static String mailpitApi() {
        return "http://127.0.0.1:" + MAILPIT.getMappedPort(8025);
    }

    private SharedContainers() {
    }
}
