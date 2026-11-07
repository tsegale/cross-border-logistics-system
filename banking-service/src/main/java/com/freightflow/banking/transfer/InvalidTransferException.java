package com.freightflow.banking.transfer;

import org.springframework.http.HttpStatus;

public class InvalidTransferException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;
    private final String title;

    public InvalidTransferException(HttpStatus status, String slug, String title, String message) {
        super(message);
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }
}
