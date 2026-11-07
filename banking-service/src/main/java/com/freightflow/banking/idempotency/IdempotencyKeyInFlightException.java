package com.freightflow.banking.idempotency;

public class IdempotencyKeyInFlightException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyInFlightException(String idempotencyKey) {
        super("A request with Idempotency-Key '" + idempotencyKey + "' is currently in flight; retry shortly");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
