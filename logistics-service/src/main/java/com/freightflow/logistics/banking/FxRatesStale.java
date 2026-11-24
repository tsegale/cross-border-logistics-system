package com.freightflow.logistics.banking;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import java.util.function.Predicate;

/**
 * banking's {@code 503 fx-rates-stale} (ADR 0034): banking answered, and refused to price a cross-currency hold on an
 * old rate. That is not banking failing, so the breaker ignores it (a stale rate must not stop same-currency payments)
 * and no in-call retry repeats it within the same seconds; the payment relay retries the command later.
 */
public class FxRatesStale implements Predicate<Throwable> {

    static final String TYPE = "https://freightflow.dev/problems/fx-rates-stale";

    @Override
    public boolean test(Throwable ex) {
        return is(ex);
    }

    public static boolean is(Throwable ex) {
        return ex instanceof HttpServerErrorException server
                && server.getStatusCode().isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)
                && server.getResponseBodyAsString().contains(TYPE);
    }
}
