package com.freightflow.banking.fx.rates;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One day's reference rate from one source (ADR 0034): {@code rate} units of the quote currency for one of the base.
 * Never overwritten or deleted (a trigger refuses both); a quarantined rate is decided once, by a four-eyes approval.
 */
@Entity
@Table(name = "reference_rates")
public class ReferenceRate {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source", length = 32, nullable = false, updatable = false)
    private String source;

    @Column(name = "base_currency", length = 3, nullable = false, updatable = false)
    private String baseCurrency;

    @Column(name = "quote_currency", length = 3, nullable = false, updatable = false)
    private String quoteCurrency;

    @Column(name = "rate", precision = 19, scale = 8, nullable = false, updatable = false)
    private BigDecimal rate;

    @Column(name = "value_date", nullable = false, updatable = false)
    private LocalDate valueDate;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private Instant fetchedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ReferenceRateStatus status;

    @Column(name = "previous_rate", precision = 19, scale = 8, updatable = false)
    private BigDecimal previousRate;

    @Column(name = "cross_check_source", length = 32, updatable = false)
    private String crossCheckSource;

    @Column(name = "cross_check_rate", precision = 19, scale = 8, updatable = false)
    private BigDecimal crossCheckRate;

    @Column(name = "quarantine_reason", length = 500, updatable = false)
    private String quarantineReason;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_approval_id")
    private UUID decidedApprovalId;

    protected ReferenceRate() {
    }

    static ReferenceRate checked(String source, String base, String quote, BigDecimal rate, LocalDate valueDate,
                                 Instant fetchedAt, BigDecimal previousRate, CrossCheck crossCheck,
                                 String quarantineReason) {
        ReferenceRate row = new ReferenceRate();
        row.source = Objects.requireNonNull(source, "source");
        row.baseCurrency = Objects.requireNonNull(base, "base");
        row.quoteCurrency = Objects.requireNonNull(quote, "quote");
        row.rate = Objects.requireNonNull(rate, "rate");
        row.valueDate = Objects.requireNonNull(valueDate, "valueDate");
        row.fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt");
        row.previousRate = previousRate;
        if (crossCheck != null) {
            row.crossCheckSource = crossCheck.source();
            row.crossCheckRate = crossCheck.rate();
        }
        row.quarantineReason = quarantineReason;
        row.status = quarantineReason == null ? ReferenceRateStatus.CONFIRMED : ReferenceRateStatus.QUARANTINED;
        return row;
    }

    /** Another source's rate for the same pair and value date. */
    public record CrossCheck(String source, BigDecimal rate) {
    }

    void decide(boolean confirmed, UUID approvalId, Instant now) {
        if (status != ReferenceRateStatus.QUARANTINED) {
            throw new ReferenceRateExceptions.ReferenceRateNotQuarantinedException(id, status);
        }
        this.status = confirmed ? ReferenceRateStatus.CONFIRMED : ReferenceRateStatus.REJECTED;
        this.decidedApprovalId = Objects.requireNonNull(approvalId, "approvalId");
        this.decidedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public String getQuoteCurrency() {
        return quoteCurrency;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public ReferenceRateStatus getStatus() {
        return status;
    }

    public BigDecimal getPreviousRate() {
        return previousRate;
    }

    public String getCrossCheckSource() {
        return crossCheckSource;
    }

    public BigDecimal getCrossCheckRate() {
        return crossCheckRate;
    }

    public String getQuarantineReason() {
        return quarantineReason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedApprovalId() {
        return decidedApprovalId;
    }
}
