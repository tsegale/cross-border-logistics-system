package com.freightflow.logistics.banking;

import com.freightflow.logistics.support.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * banking refusing to price on a stale reference rate (ADR 0034) is banking working: the breaker shared by every
 * payment stays closed, and the refusal is not retried in the call. A plain 503 still counts against the breaker and
 * is retried, as before (ADR 0013).
 */
class FxRatesStaleBreakerIntegrationTest extends IntegrationTest {

    private static final String STALE = """
            {"type":"https://freightflow.dev/problems/fx-rates-stale","title":"Exchange rates are out of date","status":503}
            """;

    @Autowired
    private BankingCalls calls;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @BeforeEach
    @AfterEach
    void closedBreaker() {
        breakers.circuitBreaker("banking").reset();
    }

    @Test
    void staleRatesNeitherOpenTheBreakerNorAreRetried() {
        AtomicInteger attempts = new AtomicInteger();
        for (int call = 0; call < 25; call++) {
            assertThatThrownBy(() -> calls.payment(() -> {
                attempts.incrementAndGet();
                throw unavailable(STALE);
            })).isInstanceOf(HttpServerErrorException.class);
        }

        assertThat(attempts).as("one attempt per call").hasValue(25);
        // The test base never lets the shared breaker open (minimum 1000 calls), so what it recorded is the proof.
        CircuitBreaker.Metrics metrics = breakers.circuitBreaker("banking").getMetrics();
        assertThat(metrics.getNumberOfFailedCalls()).as("not a failure").isZero();
        assertThat(metrics.getNumberOfBufferedCalls()).as("ignored, not even a success").isZero();
    }

    @Test
    void aPlainServiceUnavailableStillCountsAndIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        for (int call = 0; call < 10; call++) {
            try {
                calls.payment(() -> {
                    attempts.incrementAndGet();
                    throw unavailable("{\"type\":\"https://freightflow.dev/problems/database-unavailable\"}");
                });
            } catch (RuntimeException expected) {
                // Fails every time.
            }
        }

        assertThat(attempts.get()).as("retried in the call").isGreaterThan(10);
        assertThat(breakers.circuitBreaker("banking").getMetrics().getNumberOfFailedCalls()).isEqualTo(attempts.get());
    }

    private static HttpServerErrorException unavailable(String body) {
        return HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
