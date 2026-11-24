package com.freightflow.logistics.customs;

import com.freightflow.logistics.telemetry.BoundedGauge;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Days until each market's tariff subset must be reviewed (ADR 0035), read from the table so every instance reports
 * the same; negative once overdue. {@code CustomsTariffReviewOverdue} fires below zero.
 */
@Component
public class CustomsTariffMetrics {

    public CustomsTariffMetrics(CustomsTariffs tariffs, Clock clock, MeterRegistry meters) {
        for (CustomsMarket market : CustomsMarket.values()) {
            Gauge.builder("freightflow.customs.tariff.days_to_review",
                            new BoundedGauge(() -> tariffs.daysToReview(market, LocalDate.ofInstant(clock.instant(),
                                    ZoneOffset.UTC))), BoundedGauge::value)
                    .strongReference(true)
                    .tag("market", market.name())
                    .description("Days until the loaded tariff subset must be checked against its source again")
                    .baseUnit("days")
                    .register(meters);
        }
    }
}
