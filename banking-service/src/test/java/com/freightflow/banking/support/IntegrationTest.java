package com.freightflow.banking.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests running against a real PostgreSQL 17 container with
 * the Flyway migrations applied and Hibernate {@code ddl-auto: validate} active.
 */
@SpringBootTest(properties = {
        // No TLS connector in these contexts (MockMvc, and the warm-up's own port): CallerCertificateIntegrationTest
        // starts the service with its mutual TLS (ADR 0040).
        "server.ssl.enabled=false",
        "freightflow.tls.mutual=false",
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

    /**
     * The test CA's root for every internal TLS client (ADR 0039). Here as well as in {@link SharedContainers}: a
     * method of the test class is applied before the context starts, an imported one only once its registrar bean
     * runs, and a bean that needs the SSL bundle earlier (the warm-up's key fetch) would find no root.
     */
    @DynamicPropertySource
    static void internalCa(DynamicPropertyRegistry registry) {
        SharedContainers.database(registry);
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        // This service's identity from the test CA: its bundle holds a certificate wherever it runs (ADR 0040).
        registry.add("spring.ssl.bundle.pem.internal.keystore.certificate",
                () -> TestCertificates.of("banking-service").certificatePem());
        registry.add("spring.ssl.bundle.pem.internal.keystore.private-key",
                () -> TestCertificates.of("banking-service").privateKeyPem());
        // Boot reloads only files; these are PEM content.
        registry.add("spring.ssl.bundle.pem.internal.reload-on-update", () -> "false");
    }
}
