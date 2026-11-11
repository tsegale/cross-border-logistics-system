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
        "spring.datasource.hikari.maximum-pool-size=32",
        "spring.datasource.hikari.connection-timeout=60000",
        "spring.datasource.hikari.minimum-idle=2",
        // Tests drive PaymentCommandRelay.relayOnce() themselves and do not wait for real backoff.
        "freightflow.logistics.payments.relay-enabled=false",
        "freightflow.logistics.payments.hold-expiry.renewal-enabled=false",
        "freightflow.logistics.payments.retry-backoff=1ms",
        "freightflow.logistics.payments.max-retry-backoff=2ms",
        // Tests that stub failures must not trip the shared breaker for the next test; the fault tests set their own.
        "resilience4j.circuitbreaker.instances.banking.minimum-number-of-calls=1000",
        "resilience4j.circuitbreaker.instances.banking.sliding-window-size=1000"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestSecurity.class)
@ImportTestcontainers(SharedContainers.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void bankingStub(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.banking.base-url", BankingStub::baseUrl);
        registry.add("KEYCLOAK_TOKEN_URI", () -> BankingStub.baseUrl() + "/token");
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
