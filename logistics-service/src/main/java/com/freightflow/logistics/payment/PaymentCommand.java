package com.freightflow.logistics.payment;

import com.freightflow.logistics.common.MoneyRounding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One payment command for banking-service, written in the same transaction as the shipment change that needs it
 * (ADR 0012). Rows are inserted with {@code ON CONFLICT DO NOTHING} by {@link PaymentCommands}; this entity is
 * how the relay reads and finishes them.
 */
@Entity
@Table(name = "shipment_payments")
public class PaymentCommand {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "command", length = 16, nullable = false, updatable = false)
    private PaymentCommandType command;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private PaymentCommandStatus status;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "payer_account_number", length = 16, nullable = false, updatable = false)
    private String payerAccountNumber;

    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "problem_type", length = 200)
    private String problemType;

    @Column(name = "problem_detail", length = 1000)
    private String problemDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PaymentCommand() {
    }

    public void succeed(UUID holdId, Instant now) {
        this.status = PaymentCommandStatus.SUCCEEDED;
        if (holdId != null) {
            this.holdId = holdId;
        }
        this.lastError = null;
        this.completedAt = now;
        this.updatedAt = now;
    }

    /** banking refused the command for a business reason: retrying with the same request cannot change that. */
    public void fail(String problemType, String problemDetail, Instant now) {
        this.status = PaymentCommandStatus.FAILED;
        this.problemType = truncate(problemType, 200);
        this.problemDetail = truncate(problemDetail, 1000);
        this.completedAt = now;
        this.updatedAt = now;
    }

    /** The outcome is unknown: try again at {@code nextAttemptAt}, with the same idempotency key. */
    public void retryAt(Instant nextAttemptAt, String error, Instant now) {
        this.status = PaymentCommandStatus.PENDING;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        this.lastError = truncate(error, 500);
        this.updatedAt = now;
    }

    /** Out of attempts: an operator decides (POST /api/v1/payment-commands/{id}/retry). */
    public void park(String error, Instant now) {
        this.status = PaymentCommandStatus.PARKED;
        this.lastError = truncate(error, 500);
        this.updatedAt = now;
    }

    /** Operator retry of a parked command: a fresh set of attempts, same idempotency key. */
    public void unpark(Instant now) {
        if (this.status != PaymentCommandStatus.PARKED) {
            throw new IllegalStateException("Only a PARKED command can be retried; " + id + " is " + status);
        }
        this.status = PaymentCommandStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public BigDecimal roundedAmount() {
        return MoneyRounding.roundToMinorUnits(amount, currency);
    }

    public UUID getId() {
        return id;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public PaymentCommandType getCommand() {
        return command;
    }

    public PaymentCommandStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPayerAccountNumber() {
        return payerAccountNumber;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getProblemType() {
        return problemType;
    }

    public String getProblemDetail() {
        return problemDetail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
