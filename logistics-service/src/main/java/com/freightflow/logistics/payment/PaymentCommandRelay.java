package com.freightflow.logistics.payment;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Sends due payment commands to banking-service (ADR 0012). Safe on several instances: commands are claimed with
 * {@code FOR UPDATE SKIP LOCKED} and a lease, the HTTP call runs outside any database transaction, and the outcome
 * is recorded in its own transaction. Disable with {@code freightflow.logistics.payments.relay-enabled=false}
 * (tests drive {@link #relayOnce()} themselves).
 */
@Component
public class PaymentCommandRelay {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandRelay.class);

    private final PaymentCommands outbox;
    private final PaymentCommandRepository commands;
    private final BankingPayments banking;
    private final PaymentOutcomeRecorder recorder;
    private final PaymentProperties properties;
    private final Clock clock;
    private final boolean enabled;

    public PaymentCommandRelay(PaymentCommands outbox, PaymentCommandRepository commands, BankingPayments banking,
                               PaymentOutcomeRecorder recorder, PaymentProperties properties, Clock clock,
                               MeterRegistry meters,
                               @Value("${freightflow.logistics.payments.relay-enabled:true}") boolean enabled) {
        this.outbox = outbox;
        this.commands = commands;
        this.banking = banking;
        this.recorder = recorder;
        this.properties = properties;
        this.clock = clock;
        this.enabled = enabled;
        Gauge.builder("freightflow.payment.commands.parked", outbox, PaymentCommands::countParked)
                .description("Payment commands out of attempts, waiting for an operator")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${freightflow.logistics.payments.relay-interval:500ms}")
    public void scheduledRelay() {
        if (enabled) {
            relayOnce();
        }
    }

    /** Claims and sends one batch. Returns how many commands it attempted. */
    public int relayOnce() {
        List<UUID> claimed = outbox.claimDue(properties.batchSize(), properties.lease());
        for (UUID id : claimed) {
            PaymentCommand command = commands.findById(id).orElse(null);
            if (command == null || command.getStatus() != PaymentCommandStatus.PENDING) {
                continue;
            }
            PaymentOutcome outcome = banking.send(command);
            if (outcome instanceof PaymentOutcome.Unknown unknown && !unknown.reachedBanking()) {
                // Circuit open or bulkhead full: nothing was sent, so this does not count as an attempt.
                outbox.refundAttempt(id, clock.instant().plus(properties.retryBackoff()));
                continue;
            }
            try {
                recorder.record(id, outcome);
            } catch (RuntimeException ex) {
                // The lease runs out and the command is sent again with the same key: banking replays its answer.
                log.error("Could not record the outcome of payment command {}; it will be retried", id, ex);
            }
        }
        return claimed.size();
    }
}
