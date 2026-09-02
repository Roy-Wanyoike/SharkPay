package com.sharkpay.payouts.storage;

import com.sharkpay.payouts.ports.IdempotencyStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA mapping of the {@code idempotency_keys} table (composite key scope +
 * idempotency_key — a raw header value reused across operations never
 * collides).
 */
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKeyPk.class)
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "scope", nullable = false, updatable = false, length = 20)
    public String scope;

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    public String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 256)
    public String requestFingerprint;

    @Column(name = "entity_id", nullable = false, updatable = false, length = 40)
    public String entityId;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    public static IdempotencyKeyEntity of(String scope, String key,
                                          IdempotencyStore.StoredRequest request, Instant at) {
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
        entity.scope = scope;
        entity.idempotencyKey = key;
        entity.requestFingerprint = request.requestFingerprint();
        entity.entityId = request.entityId();
        entity.createdAt = at;
        return entity;
    }

    public IdempotencyStore.StoredRequest toStoredRequest() {
        return new IdempotencyStore.StoredRequest(requestFingerprint, entityId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdempotencyKeyEntity that)) {
            return false;
        }
        return Objects.equals(scope, that.scope)
                && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, idempotencyKey);
    }
}
