package com.sharkpay.payouts.storage;

import java.io.Serializable;
import java.util.Objects;

/** Composite primary key of {@code idempotency_keys}: (scope, key). */
public class IdempotencyKeyPk implements Serializable {

    public String scope;
    public String idempotencyKey;

    public IdempotencyKeyPk() {
    }

    public IdempotencyKeyPk(String scope, String idempotencyKey) {
        this.scope = scope;
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdempotencyKeyPk that)) {
            return false;
        }
        return Objects.equals(scope, that.scope)
                && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, idempotencyKey);
    }

    @Override
    public String toString() {
        return "IdempotencyKeyPk[" + scope + ':' + idempotencyKey + ']';
    }
}
