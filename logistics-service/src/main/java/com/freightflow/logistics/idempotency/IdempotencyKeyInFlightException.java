package com.freightflow.logistics.idempotency;

public class IdempotencyKeyInFlightException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyInFlightException(String idempotencyKey) {
        super("A request with Idempotency-Key '" + idempotencyKey + "' is currently in progress");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
