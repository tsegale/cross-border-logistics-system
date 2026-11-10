package com.freightflow.banking.journal;

import com.freightflow.banking.common.MoneyRounding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.Locale;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 32, nullable = false, updatable = false)
    private JournalEntryType entryType;

    @Column(name = "reference", length = 160, nullable = false, updatable = false)
    private String reference;

    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    @Column(name = "fx_quote_id", updatable = false)
    private UUID fxQuoteId;

    @Column(name = "fx_rate", precision = 19, scale = 8, updatable = false)
    private BigDecimal fxRate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public record PostingInstruction(UUID accountId, BigDecimal signedAmount, String currency) {
    }

    protected JournalEntry() {
    }

    public static JournalEntry create(
            JournalEntryType entryType,
            String reference,
            UUID reversesEntryId,
            UUID fxQuoteId,
            BigDecimal fxRate,
            Instant createdAt) {
        JournalEntry entry = new JournalEntry();
        entry.entryType = Objects.requireNonNull(entryType, "entryType");
        entry.reference = Objects.requireNonNull(reference, "reference").trim();
        entry.reversesEntryId = reversesEntryId;
        entry.fxQuoteId = fxQuoteId;
        entry.fxRate = fxRate;
        entry.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return entry;
    }

    /**
     * Domain invariant: a journal entry has at least 2 postings, every posting is non-zero,
     * and the postings sum to zero per currency.
     */
    public static void assertBalanced(List<PostingInstruction> instructions) {
        if (instructions == null || instructions.size() < 2) {
            throw new UnbalancedJournalEntryException("A journal entry requires at least two postings");
        }
        Map<String, BigDecimal> sumsByCurrency = new LinkedHashMap<>();
        for (PostingInstruction instruction : instructions) {
            if (instruction.signedAmount() == null || instruction.signedAmount().compareTo(BigDecimal.ZERO) == 0) {
                throw new UnbalancedJournalEntryException("Every posting must have a non-zero signed amount");
            }
            String currency = Objects.requireNonNull(instruction.currency(), "currency").trim().toUpperCase(Locale.ROOT);
            BigDecimal scaled = MoneyRounding.toStorageScale(instruction.signedAmount());
            sumsByCurrency.merge(currency, scaled, BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> sum : sumsByCurrency.entrySet()) {
            if (sum.getValue().compareTo(BigDecimal.ZERO) != 0) {
                throw new UnbalancedJournalEntryException(
                        "Postings in currency " + sum.getKey() + " do not sum to zero: " + sum.getValue());
            }
        }
    }

    public UUID getId() {
        return id;
    }

    public JournalEntryType getEntryType() {
        return entryType;
    }

    public String getReference() {
        return reference;
    }

    public UUID getReversesEntryId() {
        return reversesEntryId;
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
