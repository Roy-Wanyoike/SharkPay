package com.sharkpay.gateway.storage;

import java.io.Serializable;
import java.util.Objects;

/** Composite id of {@link IdempotencyCacheEntity}: (scope, idempotency_key). */
public class IdempotencyCacheEntityId implements Serializable {

    private String scope;
    private String idempotencyKey;

    public IdempotencyCacheEntityId() {
    }

    public IdempotencyCacheEntityId(String scope, String idempotencyKey) {
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
    public boolean equals(Object other) {
        return other instanceof IdempotencyCacheEntityId that
                && Objects.equals(scope, that.scope)
                && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, idempotencyKey);
    }
}
