package com.freightflow.logistics.banking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** banking-service's indicative rates from {@code base}: 1 base = customerRate of the quote currency. */
public record FxRates(String base, Instant asOf, String source, List<Rate> rates) {

    public record Rate(String currency, BigDecimal midRate, BigDecimal spread, BigDecimal customerRate) {
    }

    public Optional<Rate> to(String currency) {
        return rates == null ? Optional.empty() : rates.stream().filter(r -> r.currency().equals(currency)).findFirst();
    }
}
