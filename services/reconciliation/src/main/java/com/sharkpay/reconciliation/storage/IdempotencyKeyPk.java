package com.sharkpay.reconciliation.storage;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of the {@code idempotency_keys} table: (scope,
 * idempotency_key).
 */
public class IdempotencyKeyPk implements Serializable {

    private String scope;
    private String idempotencyKey;

    public IdempotencyKeyPk() {
    }

    public IdempotencyKeyPk(String scope, String idempotencyKey) {
        this.scope = scope;
        this.idempotencyKey = idempotencyKey;
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
        return Objects.equals(scope, that.scope) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, idempotencyKey);
    }
}
