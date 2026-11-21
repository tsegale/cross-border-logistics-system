package com.freightflow.banking.approval;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.security.Actor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A deposit or reversal one operator asked for and a different one must approve (ADR 0021). The table's CHECK
 * constraints hold the rules too: the checker is never the maker, and only an approved request has a posted entry.
 */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false, updatable = false)
    private ApprovalKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ApprovalStatus status;

    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Column(name = "amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, updatable = false)
    private String currency;

    @Column(name = "reference", length = 160, updatable = false)
    private String reference;

    @Column(name = "journal_entry_id", updatable = false)
    private UUID journalEntryId;

    @Column(name = "reason", length = 160, updatable = false)
    private String reason;

    @Column(name = "requested_by_subject", length = 64, nullable = false, updatable = false)
    private String requestedBySubject;

    @Column(name = "requested_by_client_id", length = 64, nullable = false, updatable = false)
    private String requestedByClientId;

    @Column(name = "requested_request_id", length = 64, updatable = false)
    private String requestedRequestId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "decided_by_subject", length = 64)
    private String decidedBySubject;

    @Column(name = "decided_by_client_id", length = 64)
    private String decidedByClientId;

    @Column(name = "decided_request_id", length = 64)
    private String decidedRequestId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_comment", length = 500)
    private String decisionComment;

    @Column(name = "posted_entry_id")
    private UUID postedEntryId;

    protected ApprovalRequest() {
    }

    public static ApprovalRequest deposit(UUID accountId, BigDecimal amount, String currency, String reference,
                                          Actor maker, Instant now, Instant expiresAt) {
        ApprovalRequest request = base(ApprovalKind.DEPOSIT, maker, now, expiresAt);
        request.accountId = Objects.requireNonNull(accountId, "accountId");
        request.amount = MoneyRounding.toStorageScale(Objects.requireNonNull(amount, "amount"));
        request.currency = Objects.requireNonNull(currency, "currency");
        request.reference = Objects.requireNonNull(reference, "reference");
        return request;
    }

    public static ApprovalRequest reversal(UUID journalEntryId, String reason, Actor maker, Instant now,
                                           Instant expiresAt) {
        ApprovalRequest request = base(ApprovalKind.REVERSAL, maker, now, expiresAt);
        request.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
        request.reason = Objects.requireNonNull(reason, "reason");
        return request;
    }

    private static ApprovalRequest base(ApprovalKind kind, Actor maker, Instant now, Instant expiresAt) {
        ApprovalRequest request = new ApprovalRequest();
        request.kind = kind;
        request.status = ApprovalStatus.PENDING;
        request.requestedBySubject = Objects.requireNonNull(maker.subject(), "maker.subject");
        request.requestedByClientId = Objects.requireNonNull(maker.clientId(), "maker.clientId");
        request.requestedRequestId = maker.requestId();
        request.requestedAt = Objects.requireNonNull(now, "now");
        request.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        return request;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean wasRequestedBy(Actor actor) {
        return requestedBySubject.equals(actor.subject());
    }

    void approve(Actor checker, Instant now, String comment, UUID entryId) {
        decide(ApprovalStatus.APPROVED, checker, now, comment);
        this.postedEntryId = Objects.requireNonNull(entryId, "entryId");
    }

    void reject(Actor checker, Instant now, String comment) {
        decide(ApprovalStatus.REJECTED, checker, now, comment);
    }

    void expire(Instant now) {
        requirePending();
        this.status = ApprovalStatus.EXPIRED;
        this.decidedAt = now;
    }

    private void decide(ApprovalStatus outcome, Actor checker, Instant now, String comment) {
        requirePending();
        if (wasRequestedBy(checker)) {
            throw new ApprovalExceptions.FourEyesViolationException(id);
        }
        this.status = outcome;
        this.decidedBySubject = checker.subject();
        this.decidedByClientId = checker.clientId();
        this.decidedRequestId = checker.requestId();
        this.decidedAt = now;
        this.decisionComment = comment;
    }

    void requirePending() {
        if (status != ApprovalStatus.PENDING) {
            throw new ApprovalExceptions.ApprovalNotPendingException(id, status);
        }
    }

    public Actor getRequestedBy() {
        return new Actor(requestedBySubject, requestedByClientId, null, requestedRequestId);
    }

    public Actor getDecidedBy() {
        return decidedByClientId == null ? null
                : new Actor(decidedBySubject, decidedByClientId, null, decidedRequestId);
    }

    public UUID getId() {
        return id;
    }

    public ApprovalKind getKind() {
        return kind;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReference() {
        return reference;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public UUID getPostedEntryId() {
        return postedEntryId;
    }
}
