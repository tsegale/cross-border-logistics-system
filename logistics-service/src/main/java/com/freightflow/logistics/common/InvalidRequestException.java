package com.freightflow.logistics.common;

/**
 * A request this service refuses with a message written for the caller (400 {@code invalid-argument}). Any other
 * {@link IllegalArgumentException}, a library's or one meant for a developer, is answered with a generic detail, so
 * that no class, column or configuration name reaches a client (ADR 0042, OWASP API8:2023).
 */
public class InvalidRequestException extends IllegalArgumentException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
