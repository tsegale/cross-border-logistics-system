package com.freightflow.logistics.quote;

import java.util.UUID;

public class QuoteNotFoundException extends RuntimeException {

    private final UUID quoteId;

    public QuoteNotFoundException(UUID quoteId) {
        super("Freight quote not found: " + quoteId);
        this.quoteId = quoteId;
    }

    public UUID getQuoteId() {
        return quoteId;
    }
}
