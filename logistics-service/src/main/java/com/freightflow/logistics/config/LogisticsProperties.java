package com.freightflow.logistics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "freightflow.logistics")
public record LogisticsProperties(
        List<String> supportedCurrencies,
        Duration quoteValidity,
        Idempotency idempotency
) {

    public Set<String> normalizedCurrencies() {
        return supportedCurrencies.stream()
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public record Idempotency(
            Duration ttl,
            Duration cleanupInterval,
            Duration lockTimeout
    ) {
    }
}
