package com.freightflow.logistics.events;

/**
 * A record no retry can make readable (ADR 0026): it goes to the dead-letter topic at once instead of blocking its
 * partition through the retries.
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
