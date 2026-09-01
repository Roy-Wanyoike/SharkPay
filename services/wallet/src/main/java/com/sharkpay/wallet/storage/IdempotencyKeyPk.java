package com.sharkpay.wallet.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of the {@code idempotency_keys} table:
 * (scope, idempotency_key) — idempotency keys are scoped by operation type.
 */
@Embeddable
public class IdempotencyKeyPk implements Serializable {

    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    protected IdempotencyKeyPk() {
    }

    public IdempotencyKeyPk(String scope, String idempotencyKey) {
        this.scope = Objects.requireNonNull(scope, "scope is required");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
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
        if (!(o instanceof IdempotencyKeyPk that)) {
            return false;
        }
        return scope.equals(that.scope) && idempotencyKey.equals(that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, idempotencyKey);
    }
}
