package com.freightflow.banking.fx;

import java.util.UUID;

public class FxQuoteUnavailableException extends RuntimeException {

    private final UUID fxQuoteId;
    private final String slug;
    private final String title;

    public FxQuoteUnavailableException(UUID fxQuoteId, String slug, String title, String message) {
        super(message);
        this.fxQuoteId = fxQuoteId;
        this.slug = slug;
        this.title = title;
    }

    public UUID getFxQuoteId() {
        return fxQuoteId;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }
}
