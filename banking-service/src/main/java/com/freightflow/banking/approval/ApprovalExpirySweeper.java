package com.freightflow.banking.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Marks requests nobody decided in time as EXPIRED (ADR 0021). A request past its expiry can never be approved
 * anyway; the sweep makes the status say so. Every instance runs it: each request is expired in its own transaction
 * under its row lock, which re-checks the status, so two instances never expire one request twice.
 */
@Component
public class ApprovalExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpirySweeper.class);

    private final ApprovalRepository approvals;
    private final ApprovalService service;
    private final ApprovalProperties properties;
    private final Clock clock;

    public ApprovalExpirySweeper(ApprovalRepository approvals, ApprovalService service, ApprovalProperties properties,
                                 Clock clock) {
        this.approvals = approvals;
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${freightflow.banking.approvals.sweep-interval:60s}")
    public int sweep() {
        List<UUID> candidates = approvals.findExpiredPendingIds(clock.instant(), properties.sweepBatch());
        int count = 0;
        for (UUID id : candidates) {
            try {
                if (service.expire(id)) {
                    count++;
                }
            } catch (RuntimeException ex) {
                // One request must not stop the sweep; it is retried on the next run.
                log.error("Could not expire approval request {}", id, ex);
            }
        }
        if (count > 0) {
            log.info("Expired {} approval requests", count);
        }
        return count;
    }
}
