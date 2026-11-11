package com.freightflow.logistics.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKeyId.class)
public class IdempotencyRecord {

    @Id
    @Column(name = "scope", length = 64, nullable = false, updatable = false)
    private String scope;

    /** Whose key it is (ADR 0020): client id plus organisation or subject. */
    @Id
    @Column(name = "caller", length = 200, nullable = false, updatable = false)
    private String caller;

    @Id
    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false, columnDefinition = "bpchar(64)")
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
            String caller,
            String idempotencyKey,
            String requestHash,
            int httpStatus,
            String responseBody,
            Instant createdAt,
            Instant expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.scope = Objects.requireNonNull(scope, "scope");
        record.caller = Objects.requireNonNull(caller, "caller");
        record.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        record.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        record.status = IdempotencyStatus.COMPLETED;
        record.httpStatus = httpStatus;
        record.responseBody = Objects.requireNonNull(responseBody, "responseBody");
        record.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        record.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        return record;
    }

    public String getScope() {
        return scope;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
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
