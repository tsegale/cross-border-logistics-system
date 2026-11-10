package com.freightflow.banking.transfer;

import com.freightflow.banking.common.MoneyRounding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.Locale;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "journal_entry_id", nullable = false, unique = true, updatable = false)
    private UUID journalEntryId;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false, updatable = false)
    private UUID targetAccountId;

    @Column(name = "source_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal sourceAmount;

    @Column(name = "source_currency", length = 3, nullable = false, updatable = false)
    private String sourceCurrency;

    @Column(name = "target_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal targetAmount;

    @Column(name = "target_currency", length = 3, nullable = false, updatable = false)
    private String targetCurrency;

    @Column(name = "reference", length = 160, nullable = false, updatable = false)
    private String reference;

    @Column(name = "fx_quote_id", unique = true, updatable = false)
    private UUID fxQuoteId;

    @Column(name = "fx_rate", precision = 19, scale = 8, updatable = false)
    private BigDecimal fxRate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transfer() {
    }

    public static Transfer create(
            UUID journalEntryId,
            UUID sourceAccountId,
            UUID targetAccountId,
            BigDecimal sourceAmount,
            String sourceCurrency,
            BigDecimal targetAmount,
            String targetCurrency,
            String reference,
            UUID fxQuoteId,
            BigDecimal fxRate,
            Instant createdAt) {
        Transfer transfer = new Transfer();
        transfer.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
        transfer.sourceAccountId = Objects.requireNonNull(sourceAccountId, "sourceAccountId");
        transfer.targetAccountId = Objects.requireNonNull(targetAccountId, "targetAccountId");
        transfer.sourceAmount = MoneyRounding.toStorageScale(Objects.requireNonNull(sourceAmount, "sourceAmount"));
        transfer.sourceCurrency = Objects.requireNonNull(sourceCurrency, "sourceCurrency").trim().toUpperCase(Locale.ROOT);
        transfer.targetAmount = MoneyRounding.toStorageScale(Objects.requireNonNull(targetAmount, "targetAmount"));
        transfer.targetCurrency = Objects.requireNonNull(targetCurrency, "targetCurrency").trim().toUpperCase(Locale.ROOT);
        transfer.reference = Objects.requireNonNull(reference, "reference").trim();
        transfer.fxQuoteId = fxQuoteId;
        transfer.fxRate = fxRate;
        transfer.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return transfer;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getTargetAccountId() {
        return targetAccountId;
    }

    public BigDecimal getSourceAmount() {
        return sourceAmount;
    }

    public String getSourceCurrency() {
        return sourceCurrency;
    }

    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public String getTargetCurrency() {
        return targetCurrency;
    }

    public String getReference() {
        return reference;
    }

    public UUID getFxQuoteId() {
        return fxQuoteId;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
