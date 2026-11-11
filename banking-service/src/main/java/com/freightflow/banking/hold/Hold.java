package com.freightflow.banking.hold;

import com.freightflow.banking.common.MoneyRounding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A reservation of part of a customer's balance for a freight charge (ADR 0011). Status changes are made with
 * plain SQL under the payer's account row lock (see {@link HoldService}); this entity is for reading and creating.
 */
@Entity
@Table(name = "holds")
public class Hold {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payer_account_id", nullable = false, updatable = false)
    private UUID payerAccountId;

    @Column(name = "reference", length = 160, nullable = false, updatable = false)
    private String reference;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "payer_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal payerAmount;

    @Column(name = "payer_currency", length = 3, nullable = false, updatable = false)
    private String payerCurrency;

    @Column(name = "fx_rate", precision = 19, scale = 8, updatable = false)
    private BigDecimal fxRate;

    @Column(name = "fx_mid_rate", precision = 19, scale = 8, updatable = false)
    private BigDecimal fxMidRate;

    @Column(name = "fx_spread", precision = 10, scale = 6, updatable = false)
    private BigDecimal fxSpread;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false, updatable = false)
    private HoldStatus status;

    @Column(name = "captured_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal capturedAmount;

    @Column(name = "captured_payer_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal capturedPayerAmount;

    @Column(name = "capture_journal_entry_id", updatable = false)
    private UUID captureJournalEntryId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "closed_at", updatable = false)
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Hold() {
    }

    /** {@code fx} is null when the payer account is in the charge currency. */
    public static Hold create(UUID payerAccountId, String reference, BigDecimal amount, String currency,
                              BigDecimal payerAmount, String payerCurrency, FxLock fx, Instant expiresAt, Instant now) {
        Hold hold = new Hold();
        hold.payerAccountId = Objects.requireNonNull(payerAccountId, "payerAccountId");
        hold.reference = Objects.requireNonNull(reference, "reference").trim();
        hold.amount = MoneyRounding.toStorageScale(Objects.requireNonNull(amount, "amount"));
        hold.currency = Objects.requireNonNull(currency, "currency").toUpperCase(Locale.ROOT);
        hold.payerAmount = MoneyRounding.toStorageScale(Objects.requireNonNull(payerAmount, "payerAmount"));
        hold.payerCurrency = Objects.requireNonNull(payerCurrency, "payerCurrency").toUpperCase(Locale.ROOT);
        if (fx != null) {
            hold.fxRate = fx.rate();
            hold.fxMidRate = fx.midRate();
            hold.fxSpread = fx.spread();
        }
        hold.status = HoldStatus.ACTIVE;
        hold.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        hold.createdAt = Objects.requireNonNull(now, "now");
        return hold;
    }

    /** The rate locked on a cross-currency hold: 1 payer currency = rate charge currency. */
    public record FxLock(BigDecimal rate, BigDecimal midRate, BigDecimal spread) {
    }

    public boolean isCrossCurrency() {
        return !currency.equals(payerCurrency);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPayerAccountId() {
        return payerAccountId;
    }

    public String getReference() {
        return reference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getPayerAmount() {
        return payerAmount;
    }

    public String getPayerCurrency() {
        return payerCurrency;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public BigDecimal getFxMidRate() {
        return fxMidRate;
    }

    public BigDecimal getFxSpread() {
        return fxSpread;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public BigDecimal getCapturedAmount() {
        return capturedAmount;
    }

    public BigDecimal getCapturedPayerAmount() {
        return capturedPayerAmount;
    }

    public UUID getCaptureJournalEntryId() {
        return captureJournalEntryId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
