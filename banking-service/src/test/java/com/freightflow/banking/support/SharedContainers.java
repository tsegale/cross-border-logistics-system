package com.freightflow.banking.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
            .withStartupTimeout(STARTUP_TIMEOUT);

    static {
        POSTGRES.start();
    }

    private SharedContainers() {
    }
}
