package com.freightflow.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "freightflow.banking")
public record BankingProperties(
        List<String> supportedCurrencies,
        String transferLockingStrategy,
        Idempotency idempotency,
        Fx fx
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

    public record Fx(
            Duration quoteTtl,
            BigDecimal defaultSpread,
            Map<String, Map<String, BigDecimal>> rates,
            Map<String, BigDecimal> pairSpreads
    ) {
    }
}
