package com.freightflow.notification.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests against real Postgres, Kafka (SCRAM and ACLs) and Mailpit. The listeners and the email sender run
 * as in production; retries are quick.
 */
@SpringBootTest(properties = {
        // No TLS connector in these contexts (MockMvc, and the warm-up's own port): CallerCertificateIntegrationTest
        // starts the service with its mutual TLS (ADR 0040).
        "server.ssl.enabled=false",
        "freightflow.tls.mutual=false",
        "freightflow.events.consumer.retry-delay=200",
        "freightflow.events.consumer.retry-multiplier=2",
        "freightflow.events.consumer.retry-max-delay=1000",
        "freightflow.notifications.send-interval=100ms",
        "freightflow.notifications.retry-backoff=50ms",
        "freightflow.notifications.max-retry-backoff=100ms"
})
@AutoConfigureMockMvc
@org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
@org.springframework.context.annotation.Import({TestSecurity.class, TestSpans.class})
@ImportTestcontainers(SharedContainers.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    /**
     * The test CA's root for every internal TLS client (ADR 0039). Here as well as in {@link SharedContainers}: a
     * method of the test class is applied before the context starts, an imported one only once its registrar bean
     * runs, and a bean that needs the SSL bundle earlier would find no root.
     */
    @DynamicPropertySource
    static void internalCa(DynamicPropertyRegistry registry) {
        SharedContainers.database(registry);
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        // This service's identity from the test CA: its bundle holds a certificate wherever it runs (ADR 0040).
        registry.add("spring.ssl.bundle.pem.internal.keystore.certificate",
                () -> TestCertificates.of("notification-service").certificatePem());
        registry.add("spring.ssl.bundle.pem.internal.keystore.private-key",
                () -> TestCertificates.of("notification-service").privateKeyPem());
        // Boot reloads only files; these are PEM content.
        registry.add("spring.ssl.bundle.pem.internal.reload-on-update", () -> "false");
    }
}
