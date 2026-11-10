package com.freightflow.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

    public static final String STRATEGY_PESSIMISTIC = "pessimistic";
    public static final String STRATEGY_CONDITIONAL_UPDATE = "conditional-update";
    private static final Set<String> STRATEGIES = Set.of(STRATEGY_PESSIMISTIC, STRATEGY_CONDITIONAL_UPDATE);

    /**
     * Fails startup on an unknown strategy. A typo used to fall back to {@code pessimistic} silently, which is
     * how the Phase 1 benchmark ended up comparing one strategy with itself.
     */
    public BankingProperties {
        String strategy = transferLockingStrategy == null
                ? STRATEGY_PESSIMISTIC
                : transferLockingStrategy.trim().toLowerCase(Locale.ROOT);
        if (!STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("freightflow.banking.transfer-locking-strategy must be one of "
                    + STRATEGIES + ", was '" + transferLockingStrategy + "'");
        }
        transferLockingStrategy = strategy;
    }

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

    public record Fx(
            Duration quoteTtl,
            BigDecimal defaultSpread,
            Map<String, Map<String, BigDecimal>> rates,
            Map<String, BigDecimal> pairSpreads
    ) {
    }
}
