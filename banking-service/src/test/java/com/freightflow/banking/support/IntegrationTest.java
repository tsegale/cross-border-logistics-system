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
        "spring.datasource.hikari.minimum-idle=2",
        "spring.datasource.hikari.connection-timeout=60000",
        // Fixed rates, so money assertions have known numbers; the ECB path has its own tests (ADR 0034), and
        // reference rates are never deleted, so tests sharing this database must not store them.
        "freightflow.banking.fx.reference.provider=static"
})
@AutoConfigureMockMvc
@org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
@org.springframework.context.annotation.Import({TestSecurity.class, Funding.class, TestSpans.class})
@ImportTestcontainers(SharedContainers.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {
}
