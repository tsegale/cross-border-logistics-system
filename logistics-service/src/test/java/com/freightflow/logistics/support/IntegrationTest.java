package com.freightflow.logistics.support;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        // No TLS connector in these contexts (MockMvc, and the warm-up's own port): CallerCertificateIntegrationTest
        // starts the service with its mutual TLS (ADR 0040).
        "server.ssl.enabled=false",
        "freightflow.tls.mutual=false",
        "spring.datasource.hikari.maximum-pool-size=32",
        "spring.datasource.hikari.connection-timeout=60000",
        "spring.datasource.hikari.minimum-idle=2",
        // Tests drive PaymentCommandRelay.relayOnce() themselves and do not wait for real backoff.
        "freightflow.logistics.payments.relay-enabled=false",
        "freightflow.logistics.payments.hold-expiry.renewal-enabled=false",
        // Tests drive DcsaEventProcessor.processDue() themselves: a cached context's schedule would take their events.
        "freightflow.logistics.dcsa.processing-enabled=false",
        "freightflow.logistics.payments.retry-backoff=1ms",
        "freightflow.logistics.payments.max-retry-backoff=2ms",
        // Tests that stub failures must not trip the shared breaker for the next test; the fault tests set their own.
        "resilience4j.circuitbreaker.instances.banking.minimum-number-of-calls=1000",
        "resilience4j.circuitbreaker.instances.banking.sliding-window-size=1000"
})
@AutoConfigureMockMvc
@org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
@org.springframework.context.annotation.Import({TestSecurity.class, TestSpans.class})
@ImportTestcontainers(SharedContainers.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void bankingStub(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.banking.base-url", BankingStub::baseUrl);
        registry.add("KEYCLOAK_TOKEN_URI", () -> BankingStub.baseUrl() + "/token");
        // The test CA's root for every internal TLS client (ADR 0039), before the context starts: SharedContainers'
        // own registration applies only once its registrar bean runs, after a bean like the warm-up may need it.
        SharedContainers.database(registry);
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        // This service's identity from the test CA: its bundle holds a certificate wherever it runs (ADR 0040).
        registry.add("spring.ssl.bundle.pem.internal.keystore.certificate",
                () -> TestCertificates.of("logistics-service").certificatePem());
        registry.add("spring.ssl.bundle.pem.internal.keystore.private-key",
                () -> TestCertificates.of("logistics-service").privateKeyPem());
        // Boot reloads only files; these are PEM content.
        registry.add("spring.ssl.bundle.pem.internal.reload-on-update", () -> "false");
    }

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    /** logistics' client-credentials token for banking (ADR 0019), from a stand-in token endpoint. */
    @org.junit.jupiter.api.BeforeEach
    void tokenEndpoint() {
        BankingStubs.stubTokenEndpoint();
    }

    /** Stubs and the breaker are shared by every test in the context: leave both as found. */
    @AfterEach
    void resetBankingStubs() {
        BankingStub.SERVER.resetAll();
        circuitBreakers.circuitBreaker("banking").reset();
    }
}
