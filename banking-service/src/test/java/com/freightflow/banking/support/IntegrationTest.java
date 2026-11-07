package com.freightflow.banking.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests running against a real PostgreSQL 17 container with
 * the Flyway migrations applied and Hibernate {@code ddl-auto: validate} active.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=32",
        "spring.datasource.hikari.connection-timeout=15000"
})
@AutoConfigureMockMvc
@ImportTestcontainers(SharedContainers.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {
}
