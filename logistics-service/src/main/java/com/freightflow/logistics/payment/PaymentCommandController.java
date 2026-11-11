package com.freightflow.logistics.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operator endpoints for payment commands (ADR 0012). Internal: the gateway does not route
 * /api/v1/payment-commands (ADR 0015).
 */
@RestController
@RequestMapping("/api/v1/payment-commands")
@Tag(name = "Payment commands (internal)", description = "Inspect and retry parked payment commands. Not routed by the gateway.")
public class PaymentCommandController {

    private final PaymentCommandRepository commands;
    private final Clock clock;

    public PaymentCommandController(PaymentCommandRepository commands, Clock clock) {
        this.commands = commands;
        this.clock = clock;
    }

    public record PaymentCommandResponse(UUID id, UUID shipmentId, PaymentCommandType command,
                                         PaymentCommandStatus status, BigDecimal amount, String currency,
                                         UUID holdId, int attempts, Instant nextAttemptAt, String lastError,
                                         String problemType, String problemDetail, Instant updatedAt) {
        static PaymentCommandResponse from(PaymentCommand c) {
            return new PaymentCommandResponse(c.getId(), c.getShipmentId(), c.getCommand(), c.getStatus(),
                    c.roundedAmount(), c.getCurrency(), c.getHoldId(), c.getAttempts(), c.getNextAttemptAt(),
                    c.getLastError(), c.getProblemType(), c.getProblemDetail(), c.getUpdatedAt());
        }
    }

    @GetMapping
    @Operation(summary = "List payment commands in a status, oldest change first (default PARKED)")
    @Transactional(readOnly = true)
    public List<PaymentCommandResponse> list(
            @RequestParam(name = "status", defaultValue = "PARKED") PaymentCommandStatus status,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return commands.findByStatus(status, PageRequest.of(0, Math.clamp(size, 1, 200))).stream()
                .map(PaymentCommandResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one payment command")
    @Transactional(readOnly = true)
    public PaymentCommandResponse get(@PathVariable("id") UUID id) {
        return PaymentCommandResponse.from(find(id));
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Send a parked command again, with the same idempotency key and a fresh set of attempts")
    @Transactional
    public PaymentCommandResponse retry(@PathVariable("id") UUID id) {
        PaymentCommand command = find(id);
        if (command.getStatus() != PaymentCommandStatus.PARKED) {
            throw new PaymentCommandNotParkedException(id, command.getStatus());
        }
        command.unpark(clock.instant());
        return PaymentCommandResponse.from(command);
    }

    private PaymentCommand find(UUID id) {
        return commands.findById(id).orElseThrow(() -> new PaymentCommandNotFoundException(id));
    }

    public static class PaymentCommandNotFoundException extends RuntimeException {
        private final UUID id;

        public PaymentCommandNotFoundException(UUID id) {
            super("Payment command '" + id + "' was not found");
            this.id = id;
        }

        public UUID getId() {
            return id;
        }
    }

    public static class PaymentCommandNotParkedException extends RuntimeException {
        private final UUID id;
        private final PaymentCommandStatus status;

        public PaymentCommandNotParkedException(UUID id, PaymentCommandStatus status) {
            super("Payment command " + id + " is " + status + "; only PARKED commands can be retried");
            this.id = id;
            this.status = status;
        }

        public UUID getId() {
            return id;
        }

        public PaymentCommandStatus getStatus() {
            return status;
        }
    }
}
