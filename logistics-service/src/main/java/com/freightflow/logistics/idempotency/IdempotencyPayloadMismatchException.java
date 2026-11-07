package com.freightflow.logistics.idempotency;

public class IdempotencyPayloadMismatchException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyPayloadMismatchException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request payload");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
