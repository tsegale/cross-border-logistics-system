package com.freightflow.logistics.idempotency;

import java.io.Serializable;
import java.util.Objects;

public class IdempotencyKeyId implements Serializable {

    private String scope;
    private String idempotencyKey;

    public IdempotencyKeyId() {
    }

    public IdempotencyKeyId(String scope, String idempotencyKey) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    public String getScope() {
        return scope;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdempotencyKeyId that)) {
            return false;
        }
        return Objects.equals(scope, that.scope) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, idempotencyKey);
    }
}
