package com.freightflow.logistics.shipment;

import com.freightflow.logistics.telemetry.BoundedGauge;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The payment saga's states (ADR 0012) as gauges read from the table, one per state, so every instance reports the
 * same numbers and a dashboard shows where shipments wait (ADR 0031). A handful of states: low cardinality.
 */
@Component
public class ShipmentMetrics {

    public ShipmentMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meters) {
        for (PaymentStatus state : PaymentStatus.values()) {
            Gauge.builder("freightflow.shipments.payment.state", new BoundedGauge(() -> {
                        Long count = jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM shipments WHERE payment_status = ?", Long.class, state.name());
                        return count == null ? 0 : count;
                    }), BoundedGauge::value)
                    .strongReference(true)
                    .description("Shipments by where they are in paying for themselves")
                    .tag("state", state.name().toLowerCase(Locale.ROOT))
                    .register(meters);
        }
    }
}
