package com.freightflow.banking.fx.rates;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The ECB's euro foreign exchange reference rates, from its Data Portal (ADR 0034): series
 * {@code EXR.D.<currency>.EUR.SP00.A}, units of the currency per euro, one observation per TARGET working day. Asked
 * for as CSV with {@code detail=dataonly}; the header row names the columns, so they are found by name, not position.
 * A series with no observations is a 404 from the ECB, read as an empty list.
 *
 * <p>Resilience per ADR 0013: connect and read timeouts, a retry budget for timeouts, 5xx and 429 with exponential
 * backoff, and the {@code ecb} breaker around each attempt, so a down ECB costs one refused call per refresh, not a
 * stalled scheduler thread.
 */
public class EcbRates {

    public static final String SOURCE = "ecb";

    private final RestClient client;
    private final CircuitBreaker breaker;
    private final RetryTemplate retry;

    public EcbRates(RestClient client, CircuitBreakerRegistry breakers, ReferenceRateProperties properties,
                    MeterRegistry meters) {
        this.client = client;
        this.breaker = breakers.circuitBreaker("ecb");
        ReferenceRateProperties.Retry settings = properties.retry();
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(settings.maxRetries())
                .delay(settings.delay())
                .multiplier(2)
                .timeout(settings.budget())
                .predicate(EcbRates::safeToRetry)
                .build());
        Counter retries = Counter.builder("freightflow.fx.source.retries").tag("source", SOURCE)
                .description("In-call retries of requests to a reference rate source")
                .register(meters);
        this.retry.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                retries.increment();
            }
        });
    }

    /** One published rate: {@code rate} units of {@code currency} for one euro on {@code valueDate}. */
    public record Observation(String currency, LocalDate valueDate, BigDecimal rate) {
    }

    /** The newest {@code count} observations of the currency against the euro, oldest first. */
    public List<Observation> latest(String currency, int count) {
        String csv;
        try {
            csv = retry.execute(() -> CircuitBreaker.decorateSupplier(breaker, () -> fetch(currency, count)).get());
        } catch (RetryException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("ECB request failed", ex);
        }
        return parse(csv == null ? "" : csv, currency);
    }

    private String fetch(String currency, int count) {
        try {
            return client.get()
                    .uri(uri -> uri.path("/service/data/EXR/D.{currency}.EUR.SP00.A")
                            .queryParam("lastNObservations", count)
                            .queryParam("format", "csvdata")
                            .queryParam("detail", "dataonly")
                            .build(currency))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound noSeries) {
            return "";
        }
    }

    static List<Observation> parse(String csv, String currency) {
        List<String> lines = csv.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> header = Arrays.asList(lines.getFirst().trim().split(","));
        int currencyColumn = column(header, "CURRENCY");
        int denominatorColumn = column(header, "CURRENCY_DENOM");
        int dateColumn = column(header, "TIME_PERIOD");
        int valueColumn = column(header, "OBS_VALUE");
        List<Observation> observations = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.trim().split(",", -1);
            if (fields.length != header.size()) {
                throw new EcbResponseException("ECB row has " + fields.length + " fields, header has "
                        + header.size());
            }
            if (!currency.equals(fields[currencyColumn]) || !"EUR".equals(fields[denominatorColumn])) {
                throw new EcbResponseException("ECB returned a row for " + fields[currencyColumn] + "/"
                        + fields[denominatorColumn] + ", asked for " + currency + "/EUR");
            }
            if (fields[valueColumn].isBlank()) {
                // An observation without a value is not a rate.
                continue;
            }
            BigDecimal rate;
            LocalDate valueDate;
            try {
                rate = new BigDecimal(fields[valueColumn]);
                valueDate = LocalDate.parse(fields[dateColumn]);
            } catch (NumberFormatException | DateTimeParseException ex) {
                throw new EcbResponseException("ECB row is not a date and a rate: " + ex.getMessage());
            }
            if (rate.signum() <= 0) {
                throw new EcbResponseException("ECB rate " + rate + " for " + valueDate + " is not positive");
            }
            observations.add(new Observation(currency, valueDate, rate));
        }
        observations.sort(Comparator.comparing(Observation::valueDate));
        return observations;
    }

    private static int column(List<String> header, String name) {
        int index = header.indexOf(name);
        if (index < 0) {
            throw new EcbResponseException("ECB CSV has no " + name + " column: " + header);
        }
        return index;
    }

    static boolean safeToRetry(Throwable ex) {
        if (ex instanceof CallNotPermittedException) {
            return false;
        }
        return ex instanceof ResourceAccessException
                || ex instanceof HttpServerErrorException
                || ex instanceof HttpClientErrorException client
                        && client.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** The ECB answered, but not with what its API documents. Never retried: the same request gets the same answer. */
    public static class EcbResponseException extends RuntimeException {
        public EcbResponseException(String message) {
            super(message);
        }
    }
}
