package com.freightflow.banking.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @EmbeddedId
    private IdempotencyKeyId id;

    @Column(name = "request_hash", length = 64, nullable = false, columnDefinition = "bpchar(64)")
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private IdempotencyStatus status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    public static IdempotencyRecord completed(
            String scope,
            String idempotencyKey,
            String requestHash,
            int httpStatus,
            String responseBody,
            Instant createdAt,
            Instant expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = new IdempotencyKeyId(scope, idempotencyKey);
        record.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        record.status = IdempotencyStatus.COMPLETED;
        record.httpStatus = httpStatus;
        record.responseBody = Objects.requireNonNull(responseBody, "responseBody");
        record.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        record.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        return record;
    }

    public static IdempotencyRecord inProgress(
            String scope,
            String idempotencyKey,
            String requestHash,
            Instant createdAt,
            Instant expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = new IdempotencyKeyId(scope, idempotencyKey);
        record.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        record.status = IdempotencyStatus.IN_PROGRESS;
        record.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        record.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        return record;
    }

    public IdempotencyKeyId getId() {
        return id;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
