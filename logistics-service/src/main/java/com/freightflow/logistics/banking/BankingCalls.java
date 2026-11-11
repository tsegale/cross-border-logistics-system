package com.freightflow.logistics.banking;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The resilience policy for every call to banking-service (ADR 0013).
 *
 * <pre>
 *   retry (budget, backoff, jitter)  -&gt;  circuit breaker "banking"  -&gt;  bulkhead  -&gt;  HTTP call (timeouts)
 * </pre>
 *
 * Payment commands are retried in-call only on outcomes that are safe to repeat: no answer (timeout, connection
 * error) or a 5xx. They are POSTs, which is safe only because every one carries its idempotency key; a 4xx is never
 * retried. An open breaker or a full bulkhead is never retried in-call: the relay tries again later.
 */
@Component
@EnableConfigurationProperties(BankingCalls.RetrySettings.class)
public class BankingCalls {

    private final CircuitBreaker breaker;
    private final Bulkhead payments;
    private final Bulkhead fx;
    private final RetryTemplate paymentRetry;

    public BankingCalls(CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads, RetrySettings retry,
                        MeterRegistry meters) {
        this.breaker = breakers.circuitBreaker("banking");
        this.payments = bulkheads.bulkhead("banking-payments");
        this.fx = bulkheads.bulkhead("banking-fx");
        this.paymentRetry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(retry.maxRetries())
                .delay(retry.delay())
                .multiplier(retry.multiplier())
                .jitter(retry.jitter())
                .maxDelay(retry.maxDelay())
                .timeout(retry.budget())
                .predicate(BankingCalls::safeToRetry)
                .build());
        Counter retries = Counter.builder("freightflow.banking.retries")
                .description("In-call retries of banking-service payment calls")
                .register(meters);
        this.paymentRetry.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                retries.increment();
            }
        });
    }

    /**
     * @param maxRetries in-call retries after the first attempt
     * @param delay      first backoff
     * @param multiplier backoff growth per retry
     * @param jitter     random spread added to each backoff, so retries from many relays do not line up
     * @param maxDelay   cap on one backoff
     * @param budget     total time one call may take, retries included; beyond it the relay's durable retry takes over
     */
    @ConfigurationProperties(prefix = "freightflow.logistics.banking.retry")
    public record RetrySettings(long maxRetries, Duration delay, double multiplier, Duration jitter,
                                Duration maxDelay, Duration budget) {
        public RetrySettings {
            maxRetries = maxRetries < 0 ? 2 : maxRetries;
            delay = delay == null ? Duration.ofMillis(100) : delay;
            multiplier = multiplier < 1 ? 2.0 : multiplier;
            jitter = jitter == null ? Duration.ofMillis(50) : jitter;
            maxDelay = maxDelay == null ? Duration.ofMillis(400) : maxDelay;
            budget = budget == null ? Duration.ofSeconds(5) : budget;
        }
    }

    /** A payment command: retried in-call, through the breaker and the payments bulkhead. */
    public <T> T payment(Supplier<T> call) {
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, Bulkhead.decorateSupplier(payments, call));
        try {
            return paymentRetry.execute(guarded::get);
        } catch (RetryException ex) {
            throw unwrap(ex);
        }
    }

    /** An indicative FX rate for a quote: never retried, the quote is issued without it instead. */
    public <T> T fxRate(Supplier<T> call) {
        return CircuitBreaker.decorateSupplier(breaker, Bulkhead.decorateSupplier(fx, call)).get();
    }

    public CircuitBreaker.State breakerState() {
        return breaker.getState();
    }

    static boolean safeToRetry(Throwable ex) {
        if (ex instanceof CallNotPermittedException || ex instanceof BulkheadFullException) {
            return false;
        }
        return ex instanceof ResourceAccessException || ex instanceof HttpServerErrorException;
    }

    private static RuntimeException unwrap(RetryException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("banking-service call failed", cause);
    }
}
