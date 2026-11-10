package com.freightflow.banking.fx;

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
@Table(name = "fx_quotes")
public class FxQuote {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sell_currency", length = 3, nullable = false, updatable = false)
    private String sellCurrency;

    @Column(name = "buy_currency", length = 3, nullable = false, updatable = false)
    private String buyCurrency;

    @Column(name = "sell_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal sellAmount;

    @Column(name = "buy_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal buyAmount;

    @Column(name = "mid_rate", precision = 19, scale = 8, nullable = false, updatable = false)
    private BigDecimal midRate;

    @Column(name = "spread", precision = 10, scale = 6, nullable = false, updatable = false)
    private BigDecimal spread;

    @Column(name = "rate", precision = 19, scale = 8, nullable = false, updatable = false)
    private BigDecimal rate;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FxQuote() {
    }

    public static FxQuote create(
            String sellCurrency,
            String buyCurrency,
            BigDecimal sellAmount,
            BigDecimal buyAmount,
            BigDecimal midRate,
            BigDecimal spread,
            BigDecimal rate,
            Instant createdAt,
            Instant expiresAt) {
        FxQuote quote = new FxQuote();
        quote.sellCurrency = Objects.requireNonNull(sellCurrency, "sellCurrency").trim().toUpperCase(Locale.ROOT);
        quote.buyCurrency = Objects.requireNonNull(buyCurrency, "buyCurrency").trim().toUpperCase(Locale.ROOT);
        quote.sellAmount = MoneyRounding.toStorageScale(Objects.requireNonNull(sellAmount, "sellAmount"));
        quote.buyAmount = MoneyRounding.toStorageScale(Objects.requireNonNull(buyAmount, "buyAmount"));
        quote.midRate = Objects.requireNonNull(midRate, "midRate");
        quote.spread = Objects.requireNonNull(spread, "spread");
        quote.rate = Objects.requireNonNull(rate, "rate");
        quote.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        quote.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        return quote;
    }

    public void consume(Instant now) {
        if (this.usedAt != null) {
            throw new FxQuoteUnavailableException(
                    this.id,
                    "fx-quote-already-used",
                    "FX quote already used",
                    "FX quote " + this.id + " was already used at " + this.usedAt
            );
        }
        if (!now.isBefore(this.expiresAt)) {
            throw new FxQuoteUnavailableException(
                    this.id,
                    "fx-quote-expired",
                    "FX quote expired",
                    "FX quote " + this.id + " expired at " + this.expiresAt
            );
        }
        this.usedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() {
        return id;
    }

    public String getSellCurrency() {
        return sellCurrency;
    }

    public String getBuyCurrency() {
        return buyCurrency;
    }

    public BigDecimal getSellAmount() {
        return sellAmount;
    }

    public BigDecimal getBuyAmount() {
        return buyAmount;
    }

    public BigDecimal getMidRate() {
        return midRate;
    }

    public BigDecimal getSpread() {
        return spread;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
