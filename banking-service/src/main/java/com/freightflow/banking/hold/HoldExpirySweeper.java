package com.freightflow.banking.hold;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Releases holds past their expiry. Every instance runs it: candidates are read without a lock and each one is
 * expired in its own transaction under the account and hold locks, which re-checks the status, so two instances
 * never release one hold twice and a sweep never blocks a capture for longer than one hold.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final HoldRepository holds;
    private final HoldService holdService;
    private final HoldProperties properties;
    private final Clock clock;
    private final Counter expired;

    public HoldExpirySweeper(HoldRepository holds, HoldService holdService, HoldProperties properties, Clock clock,
                             MeterRegistry meters) {
        this.holds = holds;
        this.holdService = holdService;
        this.properties = properties;
        this.clock = clock;
        this.expired = Counter.builder("freightflow.holds.expired")
                .description("Holds released by the expiry sweeper")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${freightflow.banking.holds.sweep-interval:60s}")
    public int sweep() {
        List<UUID> candidates = holds.findExpiredActiveIds(clock.instant(), properties.sweepBatch());
        int count = 0;
        for (UUID id : candidates) {
            try {
                if (holdService.expire(id)) {
                    count++;
                    expired.increment();
                }
            } catch (RuntimeException ex) {
                // One bad hold must not stop the sweep; it is retried on the next run.
                log.error("Could not expire hold {}", id, ex);
            }
        }
        if (count > 0) {
            log.info("Expired {} holds", count);
        }
        return count;
    }
}
