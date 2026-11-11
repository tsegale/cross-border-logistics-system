package com.freightflow.logistics.payment;

import com.freightflow.logistics.shipment.Shipment;
import com.freightflow.logistics.shipment.ShipmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Records the outcome of one attempt and moves the shipment on (ADR 0012), in one transaction. A tracking event
 * may change the same shipment concurrently; the {@code @Version} conflict is retried.
 */
@Component
public class PaymentOutcomeRecorder {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeRecorder.class);

    private final PaymentCommandRepository commands;
    private final PaymentCommands outbox;
    private final ShipmentRepository shipments;
    private final PaymentProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public PaymentOutcomeRecorder(PaymentCommandRepository commands, PaymentCommands outbox,
                                  ShipmentRepository shipments, PaymentProperties properties, MeterRegistry meters,
                                  Clock clock) {
        this.commands = commands;
        this.outbox = outbox;
        this.shipments = shipments;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    @Retryable(includes = ObjectOptimisticLockingFailureException.class,
            maxRetries = 6, delay = 20, jitter = 20, multiplier = 2, maxDelay = 500)
    @Transactional
    public void record(UUID commandId, PaymentOutcome outcome) {
        PaymentCommand command = commands.findById(commandId)
                .orElseThrow(() -> new IllegalStateException("Payment command " + commandId + " vanished"));
        if (command.getStatus() != PaymentCommandStatus.PENDING) {
            // Another relay finished it after this one's lease ran out; its result stands.
            return;
        }
        Instant now = clock.instant();
        String outcomeTag = switch (outcome) {
            case PaymentOutcome.Succeeded ok -> succeeded(command, ok, now);
            case PaymentOutcome.Refused no -> refused(command, no, now);
            case PaymentOutcome.Unknown unknown -> unknown(command, unknown, now);
        };
        meters.counter("freightflow.payment.commands", "command", command.getCommand().name(), "outcome", outcomeTag)
                .increment();
    }

    private String succeeded(PaymentCommand command, PaymentOutcome.Succeeded ok, Instant now) {
        command.succeed(ok.holdId(), now);
        Shipment shipment = shipment(command);
        switch (command.getCommand()) {
            case AUTHORISE -> {
                if (shipment.paymentAuthorised(ok.holdId(), ok.holdExpiresAt(), now)) {
                    // Cancelled while the hold was in flight: give the money back.
                    outbox.enqueue(shipment.getId(), PaymentCommandType.RELEASE, command.getAmount(),
                            command.getCurrency(), command.getPayerAccountNumber(), ok.holdId(), null);
                }
            }
            case CAPTURE -> shipment.paymentCaptured(now);
            case RELEASE -> shipment.paymentReleased(now);
            case EXTEND -> {
                if (ok.holdExpiresAt() != null) {
                    shipment.holdExtended(ok.holdExpiresAt(), now);
                }
            }
        }
        log.info("{} for shipment {} succeeded (hold {}, attempt {})", command.getCommand(), shipment.getId(),
                ok.holdId(), command.getAttempts());
        return "succeeded";
    }

    private String refused(PaymentCommand command, PaymentOutcome.Refused no, Instant now) {
        command.fail(no.problemType(), no.detail(), now);
        Shipment shipment = shipment(command);
        if (command.getCommand() == PaymentCommandType.AUTHORISE) {
            shipment.paymentRefused(no.problemType(), no.detail(), now);
        } else {
            shipment.paymentSettlementFailed(no.problemType(), no.detail(), now);
        }
        log.warn("{} for shipment {} refused by banking: {} {}", command.getCommand(), shipment.getId(),
                no.problemType(), no.detail());
        return "refused";
    }

    private String unknown(PaymentCommand command, PaymentOutcome.Unknown unknown, Instant now) {
        if (command.getAttempts() >= properties.maxAttempts()) {
            command.park(unknown.reason(), now);
            log.error("{} for shipment {} parked after {} attempts with an unknown outcome: {}",
                    command.getCommand(), command.getShipmentId(), command.getAttempts(), unknown.reason());
            return "parked";
        }
        Instant retryAt = now.plus(backoff(command.getAttempts()));
        command.retryAt(retryAt, unknown.reason(), now);
        log.warn("{} for shipment {} outcome unknown (attempt {}), retrying at {}: {}", command.getCommand(),
                command.getShipmentId(), command.getAttempts(), retryAt, unknown.reason());
        return "unknown";
    }

    /** Exponential backoff with full jitter: base x 2^(attempt-1), capped, then a random point in [cap/2, cap]. */
    Duration backoff(int attempt) {
        long base = properties.retryBackoff().toMillis();
        long cap = Math.min(properties.maxRetryBackoff().toMillis(), base << Math.min(Math.max(attempt - 1, 0), 20));
        return Duration.ofMillis(cap / 2 + ThreadLocalRandom.current().nextLong(cap / 2 + 1));
    }

    private Shipment shipment(PaymentCommand command) {
        return shipments.findById(command.getShipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment " + command.getShipmentId() + " vanished"));
    }
}
