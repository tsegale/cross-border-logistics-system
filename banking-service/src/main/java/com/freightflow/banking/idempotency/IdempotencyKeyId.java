package com.freightflow.banking.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class IdempotencyKeyId implements Serializable {

    @Column(name = "scope", length = 64, nullable = false)
    private String scope;

    /** Whose key it is (ADR 0020): client id plus organisation or subject. */
    @Column(name = "caller", length = 200, nullable = false)
    private String caller;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    protected IdempotencyKeyId() {
    }

    public IdempotencyKeyId(String scope, String caller, String idempotencyKey) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    public String getScope() {
        return scope;
    }

    public String getCaller() {
        return caller;
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
        return Objects.equals(scope, that.scope) && Objects.equals(caller, that.caller)
                && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, caller, idempotencyKey);
    }
}
