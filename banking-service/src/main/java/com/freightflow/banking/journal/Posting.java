package com.freightflow.banking.journal;

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
@Table(name = "postings")
public class Posting {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    /** Balance after this posting; null for an unlocked system posting, the shard's balance for a sharded one. */
    @Column(name = "running_balance", precision = 19, scale = 4, updatable = false)
    private BigDecimal runningBalance;

    @Column(name = "shard", updatable = false)
    private Short shard;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Posting() {
    }

    public static Posting create(
            UUID journalEntryId,
            UUID accountId,
            BigDecimal signedAmount,
            String currency,
            BigDecimal runningBalance,
            Instant createdAt) {
        Posting posting = new Posting();
        posting.journalEntryId = Objects.requireNonNull(journalEntryId, "journalEntryId");
        posting.accountId = Objects.requireNonNull(accountId, "accountId");
        posting.amount = MoneyRounding.toStorageScale(Objects.requireNonNull(signedAmount, "signedAmount"));
        posting.currency = Objects.requireNonNull(currency, "currency").trim().toUpperCase(Locale.ROOT);
        posting.runningBalance = MoneyRounding.toStorageScale(Objects.requireNonNull(runningBalance, "runningBalance"));
        posting.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return posting;
    }

    /** A system-account posting that updated no balance row (UNLOCKED mode). */
    public static Posting unlocked(UUID journalEntryId, UUID accountId, BigDecimal signedAmount, String currency,
                                   Instant createdAt) {
        Posting posting = create(journalEntryId, accountId, signedAmount, currency, BigDecimal.ZERO, createdAt);
        posting.runningBalance = null;
        return posting;
    }

    /** A system-account posting applied to one balance shard (SHARDED mode). */
    public static Posting sharded(UUID journalEntryId, UUID accountId, BigDecimal signedAmount, String currency,
                                  int shard, BigDecimal shardBalance, Instant createdAt) {
        Posting posting = create(journalEntryId, accountId, signedAmount, currency, shardBalance, createdAt);
        posting.shard = (short) shard;
        return posting;
    }

    public Short getShard() {
        return shard;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
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

    public BigDecimal getRunningBalance() {
        return runningBalance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
