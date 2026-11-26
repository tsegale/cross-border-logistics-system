package com.freightflow.logistics.deadletter;

import com.freightflow.logistics.common.InvalidRequestException;
import com.freightflow.logistics.deadletter.DeadLetterExceptions.DeadLetterAlreadyRedrivenException;
import com.freightflow.logistics.deadletter.DeadLetterExceptions.DeadLetterNotFoundException;
import com.freightflow.logistics.deadletter.DeadLetterExceptions.RedriveFailedException;
import com.freightflow.logistics.deadletter.DeadLetterStore.DeadLetter;
import com.freightflow.logistics.events.EventTopics;
import com.freightflow.logistics.events.HoldEvent;
import com.freightflow.logistics.payment.BankingHoldEvents;
import com.freightflow.logistics.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * logistics' dead letters (ADR 0026): operators list and redrive them, auditors list them. A redrive hands the stored
 * record to the handler the consumer uses, in one transaction with the status change, and records who did it
 * (ADR 0020). The handler is idempotent by event id, so a redrive of an event that was handled after all changes
 * nothing. Like notification-service's, it repeats what the consumer would have done and needs no second operator
 * (ADR 0021): any payment it causes goes through the payment commands.
 *
 * <p>The path names the service because the gateway routes {@code /api/v1/dead-letters} to notification-service.
 */
@RestController
@RequestMapping("/api/v1/logistics/dead-letters")
public class DeadLetterController {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterController.class);
    private static final Set<String> STATUSES = Set.of("NEW", "REDRIVEN");

    private final DeadLetterStore store;
    private final BankingHoldEvents holdEvents;
    private final TransactionTemplate transactions;

    public DeadLetterController(DeadLetterStore store, BankingHoldEvents holdEvents, TransactionTemplate transactions) {
        this.store = store;
        this.holdEvents = holdEvents;
        this.transactions = transactions;
    }

    public record DeadLetterResponse(UUID id, String originalTopic, int partition, long offset, String key,
                                     UUID eventId, String eventType, String error, Instant receivedAt, String status,
                                     int redriveAttempts, String lastRedriveError, Instant redrivenAt,
                                     String redrivenBy, String value) {

        static DeadLetterResponse of(DeadLetter letter) {
            return new DeadLetterResponse(letter.id(), letter.originalTopic(), letter.partition(), letter.offset(),
                    letter.key(), letter.eventId(), letter.eventType(), letter.error(), letter.receivedAt(),
                    letter.status(), letter.redriveAttempts(), letter.lastRedriveError(), letter.redrivenAt(),
                    letter.redrivenBySubject(), letter.value());
        }
    }

    public record DeadLetterList(List<DeadLetterResponse> items) {
    }

    @GetMapping
    public DeadLetterList list(@RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "50") int limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw new InvalidRequestException("status must be one of " + STATUSES);
        }
        if (limit < 1 || limit > 200) {
            throw new InvalidRequestException("limit must be between 1 and 200");
        }
        return new DeadLetterList(store.list(status, limit).stream().map(DeadLetterResponse::of).toList());
    }

    @GetMapping("/{id}")
    public DeadLetterResponse get(@PathVariable UUID id) {
        return store.find(id).map(DeadLetterResponse::of).orElseThrow(() -> new DeadLetterNotFoundException(id));
    }

    @PostMapping("/{id}/redrive")
    public DeadLetterResponse redrive(Caller caller, @PathVariable UUID id) {
        try {
            transactions.executeWithoutResult(status -> {
                DeadLetter letter = store.lock(id).orElseThrow(() -> new DeadLetterNotFoundException(id));
                if (!"NEW".equals(letter.status())) {
                    throw new DeadLetterAlreadyRedrivenException(id);
                }
                handle(letter);
                store.redriven(id, caller.actor());
            });
        } catch (DeadLetterNotFoundException | DeadLetterAlreadyRedrivenException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            int attempts = store.redriveFailed(id, ex.toString());
            log.warn("Redrive of dead letter {} by {} failed (attempt {}): {}", id, caller.subject(), attempts,
                    ex.toString());
            throw new RedriveFailedException(id, attempts, ex.getMessage());
        }
        log.info("Dead letter {} redriven by {} ({})", id, caller.subject(), caller.clientId());
        return get(id);
    }

    /** The consumer's handler for the record's topic; logistics consumes one topic today. */
    private void handle(DeadLetter letter) {
        if (!EventTopics.BANKING_HOLD.equals(letter.originalTopic())) {
            throw new IllegalStateException("No handler for topic " + letter.originalTopic());
        }
        holdEvents.handle(HoldEvent.parse(letter.value()), letter.originalTopic());
    }
}
