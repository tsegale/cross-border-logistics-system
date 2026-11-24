package com.freightflow.banking.fx.rates;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;

/**
 * Live reference rates (ADR 0034).
 *
 * @param provider             {@code ecb} (rates from the ECB, the default) or {@code static} (the configured table of
 *                             {@code freightflow.banking.fx.rates}, for tests that need fixed numbers)
 * @param ecbBaseUrl           the ECB Data Portal, {@code https://data-api.ecb.europa.eu}
 * @param bonUrl               the Bank of Namibia's rates table, the second source that NAD is checked against
 * @param publicationTime      when the ECB publishes a day's rates, in {@link #zone()}: around 16:00 CET
 * @param publicationGrace     how long after it a day's rate is due; before that, yesterday's is the newest expected
 * @param zone                 the ECB's time zone
 * @param maxAgeWorkingDays    how many working days a rate may be behind the newest expected one before FX is refused
 * @param maxDailyMove         a move from the previous confirmed rate beyond this fraction is quarantined
 * @param pegTolerance         an implied NAD/ZAR rate further than this fraction from 1 is quarantined
 * @param refreshInterval      how often each instance checks whether a newer rate is due
 * @param lookback             observations asked for on each refresh, so a few missed days fill in
 * @param connectTimeout       connect timeout for both sources
 * @param readTimeout          read timeout for both sources
 * @param retry                in-call retries of the ECB request
 */
@ConfigurationProperties(prefix = "freightflow.banking.fx.reference")
public record ReferenceRateProperties(
        String provider,
        URI ecbBaseUrl,
        URI bonUrl,
        LocalTime publicationTime,
        Duration publicationGrace,
        ZoneId zone,
        int maxAgeWorkingDays,
        BigDecimal maxDailyMove,
        BigDecimal pegTolerance,
        Duration refreshInterval,
        int lookback,
        Duration connectTimeout,
        Duration readTimeout,
        Retry retry
) {

    public static final String ECB = "ecb";
    public static final String STATIC = "static";

    public ReferenceRateProperties {
        provider = provider == null ? ECB : provider.trim().toLowerCase(Locale.ROOT);
        if (!provider.equals(ECB) && !provider.equals(STATIC)) {
            throw new IllegalArgumentException("freightflow.banking.fx.reference.provider must be 'ecb' or 'static', "
                    + "was '" + provider + "'");
        }
        if (provider.equals(ECB)) {
            Objects.requireNonNull(ecbBaseUrl, "freightflow.banking.fx.reference.ecb-base-url");
        }
        publicationTime = publicationTime == null ? LocalTime.of(16, 0) : publicationTime;
        publicationGrace = publicationGrace == null ? Duration.ofHours(1) : publicationGrace;
        zone = zone == null ? ZoneId.of("Europe/Berlin") : zone;
        if (maxAgeWorkingDays < 0) {
            throw new IllegalArgumentException("freightflow.banking.fx.reference.max-age-working-days must not be "
                    + "negative, was " + maxAgeWorkingDays);
        }
        maxDailyMove = maxDailyMove == null ? new BigDecimal("0.05") : maxDailyMove;
        pegTolerance = pegTolerance == null ? new BigDecimal("0.02") : pegTolerance;
        refreshInterval = refreshInterval == null ? Duration.ofMinutes(15) : refreshInterval;
        lookback = lookback <= 0 ? 10 : lookback;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        retry = retry == null ? new Retry(2, Duration.ofMillis(500), Duration.ofSeconds(20)) : retry;
    }

    /**
     * @param maxRetries retries after the first attempt
     * @param delay      first backoff, doubled on each retry
     * @param budget     total time one refresh may spend on the ECB, retries included
     */
    public record Retry(long maxRetries, Duration delay, Duration budget) {
    }
}
