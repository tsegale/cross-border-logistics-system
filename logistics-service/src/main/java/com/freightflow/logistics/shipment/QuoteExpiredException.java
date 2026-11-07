package com.freightflow.logistics.shipment;

import java.time.Instant;
import java.util.UUID;

public class QuoteExpiredException extends RuntimeException {

    private final UUID quoteId;
    private final Instant validUntil;

    public QuoteExpiredException(UUID quoteId, Instant validUntil) {
        super("Freight quote " + quoteId + " expired at " + validUntil);
        this.quoteId = quoteId;
        this.validUntil = validUntil;
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public Instant getValidUntil() {
        return validUntil;
    }
}
