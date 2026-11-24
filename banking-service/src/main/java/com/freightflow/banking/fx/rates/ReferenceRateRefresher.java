package com.freightflow.banking.fx.rates;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Optional;

/**
 * Fetches the ECB's newest rates when one is due (ADR 0034). Every instance checks every
 * {@code refresh-interval}; the check is one indexed query, and only when the newest stored rate is older than the
 * one due does it call the ECB, so the source sees a handful of requests around 17:00 CET on working days and none at
 * weekends. Two instances that call at once both store through {@link ReferenceRateService#ingest}, which serialises
 * them, so the second stores nothing. A failure is logged and counted; the next check tries again, and the stale-rate
 * alert fires if none succeeds in time.
 */
public class ReferenceRateRefresher {

    private static final Logger log = LoggerFactory.getLogger(ReferenceRateRefresher.class);

    private final ReferenceRateService service;
    private final EcbRates ecb;
    private final BonRates bon;
    private final ReferenceRateProperties properties;
    private final MeterRegistry meters;

    public ReferenceRateRefresher(ReferenceRateService service, EcbRates ecb, BonRates bon,
                                  ReferenceRateProperties properties, MeterRegistry meters) {
        this.service = service;
        this.ecb = ecb;
        this.bon = bon;
        this.properties = properties;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${freightflow.banking.fx.reference.refresh-interval:15m}",
            initialDelayString = "${freightflow.banking.fx.reference.initial-delay:5s}")
    public void refreshIfDue() {
        try {
            if (!service.refreshDue()) {
                return;
            }
            refresh();
        } catch (RuntimeException ex) {
            meters.counter("freightflow.fx.reference_rates.refreshes", "outcome", "failed").increment();
            log.warn("Reference rate refresh failed, retrying in {}: {}", properties.refreshInterval(), ex.toString());
        }
    }

    /** One refresh, due or not: the ECB, then the Bank of Namibia only if the ECB had something new. */
    public List<ReferenceRate> refresh() {
        List<EcbRates.Observation> observations = ecb.latest(ReferenceRateService.ZAR, properties.lookback());
        if (observations.isEmpty()) {
            meters.counter("freightflow.fx.reference_rates.refreshes", "outcome", "empty").increment();
            log.warn("ECB returned no {}/EUR observations", ReferenceRateService.ZAR);
            return List.of();
        }
        Optional<BonRates.NadPerEur> crossCheck = bon == null ? Optional.empty() : bon.euroRate();
        List<ReferenceRate> stored = service.ingest(observations, crossCheck);
        meters.counter("freightflow.fx.reference_rates.refreshes", "outcome", stored.isEmpty() ? "unchanged" : "stored")
                .increment();
        return stored;
    }
}
