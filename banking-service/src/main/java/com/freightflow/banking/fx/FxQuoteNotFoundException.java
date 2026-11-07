package com.freightflow.banking.fx;

import java.util.UUID;

public class FxQuoteNotFoundException extends RuntimeException {

    private final UUID fxQuoteId;

    public FxQuoteNotFoundException(UUID fxQuoteId) {
        super("FX quote '" + fxQuoteId + "' was not found");
        this.fxQuoteId = fxQuoteId;
    }

    public UUID getFxQuoteId() {
        return fxQuoteId;
    }
}
