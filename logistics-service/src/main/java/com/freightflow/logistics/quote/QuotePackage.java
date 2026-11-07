package com.freightflow.logistics.quote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "quote_packages")
public class QuotePackage {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "quote_id", nullable = false, updatable = false)
    private UUID quoteId;

    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;

    @Column(name = "length_cm", precision = 10, scale = 2, nullable = false, updatable = false)
    private BigDecimal lengthCm;

    @Column(name = "width_cm", precision = 10, scale = 2, nullable = false, updatable = false)
    private BigDecimal widthCm;

    @Column(name = "height_cm", precision = 10, scale = 2, nullable = false, updatable = false)
    private BigDecimal heightCm;

    @Column(name = "weight_kg", precision = 10, scale = 3, nullable = false, updatable = false)
    private BigDecimal weightKg;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "description", length = 200, updatable = false)
    private String description;

    protected QuotePackage() {
    }

    public static QuotePackage create(
            UUID quoteId,
            int lineNumber,
            BigDecimal lengthCm,
            BigDecimal widthCm,
            BigDecimal heightCm,
            BigDecimal weightKg,
            int quantity,
            String description) {
        QuotePackage p = new QuotePackage();
        p.quoteId = Objects.requireNonNull(quoteId, "quoteId");
        p.lineNumber = lineNumber;
        p.lengthCm = lengthCm.setScale(2, RoundingMode.HALF_EVEN);
        p.widthCm = widthCm.setScale(2, RoundingMode.HALF_EVEN);
        p.heightCm = heightCm.setScale(2, RoundingMode.HALF_EVEN);
        p.weightKg = weightKg.setScale(3, RoundingMode.HALF_EVEN);
        p.quantity = quantity;
        p.description = description != null ? description.trim() : null;
        return p;
    }

    public UUID getId() {
        return id;
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public BigDecimal getLengthCm() {
        return lengthCm;
    }

    public BigDecimal getWidthCm() {
        return widthCm;
    }

    public BigDecimal getHeightCm() {
        return heightCm;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getDescription() {
        return description;
    }
}
