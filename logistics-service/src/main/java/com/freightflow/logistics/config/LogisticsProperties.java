package com.freightflow.logistics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "freightflow.logistics")
public record LogisticsProperties(
        List<String> supportedCurrencies,
        Duration quoteValidity,
        Idempotency idempotency,
        Map<String, Map<String, BigDecimal>> fxRates
) {

    public Set<String> normalizedCurrencies() {
        return supportedCurrencies.stream()
                .map(c -> c.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
    }

    public record Idempotency(
            Duration ttl,
            Duration cleanupInterval,
            Duration lockTimeout
    ) {
    }
}
