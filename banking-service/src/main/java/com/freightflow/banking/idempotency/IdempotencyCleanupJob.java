package com.freightflow.banking.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    private static final int BATCH_SIZE = 500;

    private final IdempotencyRepository repository;
    private final Clock clock;

    public IdempotencyCleanupJob(IdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${freightflow.banking.idempotency.cleanup-interval:15m}")
    @Transactional
    public int purgeExpiredKeys() {
        int deleted = repository.deleteExpiredBatch(clock.instant(), BATCH_SIZE);
        if (deleted > 0) {
            log.info("Purged {} expired idempotency keys", deleted);
        }
        return deleted;
    }
}
