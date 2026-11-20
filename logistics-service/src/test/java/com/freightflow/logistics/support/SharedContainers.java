package com.freightflow.logistics.support;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

    @ServiceConnection
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
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        registry.add("KAFKA_USERNAME", () -> "logistics");
        registry.add("freightflow.kafka.password", () -> KAFKA.password("logistics"));
    }

    private SharedContainers() {
    }
}
