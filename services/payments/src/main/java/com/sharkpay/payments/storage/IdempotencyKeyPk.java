package com.sharkpay.payments.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@code idempotency_keys}: (scope, idempotency_key) — a
 * raw header value reused across operations never collides.
 */
@Embeddable
public class IdempotencyKeyPk implements Serializable {

    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, length = 128)
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
        return scope.equals(that.scope) && idempotencyKey.equals(that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, idempotencyKey);
    }

    @Override
    public String toString() {
        return "IdempotencyKeyPk[" + scope + ":" + idempotencyKey + "]";
    }
}
