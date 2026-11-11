package com.freightflow.logistics.payment;

import com.freightflow.logistics.common.RequestIdFilter;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private final ExecutorService executor;

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
        this.executor = Executors.newFixedThreadPool(properties.parallelism(),
                Thread.ofPlatform().name("payment-relay-", 0).daemon(true).factory());
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

    /**
     * Claims one batch and sends its commands in parallel, at most {@code payments.parallelism} at a time (the same
     * as the payments bulkhead, ADR 0013). Returns when every command of the batch has an outcome recorded, so a run
     * never overlaps the next one. Returns how many commands it claimed.
     */
    public int relayOnce() {
        List<UUID> claimed = outbox.claimDue(properties.batchSize(), properties.lease());
        List<Callable<Void>> sends = claimed.stream().<Callable<Void>>map(id -> () -> {
            sendOne(id);
            return null;
        }).toList();
        try {
            for (Future<Void> done : executor.invokeAll(sends)) {
                done.get();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            // sendOne handles its own failures; anything here is a bug, and the lease brings the command back.
            log.error("Payment relay task failed", ex.getCause());
        }
        return claimed.size();
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }

    private void sendOne(UUID id) {
        PaymentCommand command = commands.findById(id).orElse(null);
        if (command == null || command.getStatus() != PaymentCommandStatus.PENDING) {
            return;
        }
        PaymentOutcome outcome;
        // The command's idempotency key is its request id in both services' logs (ADR 0015).
        try (MDC.MDCCloseable ignored = MDC.putCloseable(RequestIdFilter.MDC_KEY, command.getIdempotencyKey())) {
            outcome = banking.send(command);
        }
        if (outcome instanceof PaymentOutcome.Unknown unknown && !unknown.reachedBanking()) {
            // Circuit open or bulkhead full: nothing was sent, so this does not count as an attempt.
            outbox.refundAttempt(id, clock.instant().plus(properties.retryBackoff()));
            return;
        }
        try {
            recorder.record(id, outcome);
        } catch (RuntimeException ex) {
            // The lease runs out and the command is sent again with the same key: banking replays its answer.
            log.error("Could not record the outcome of payment command {}; it will be retried", id, ex);
        }
    }
}
