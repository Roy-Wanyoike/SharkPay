package com.sharkpay.reconciliation.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code idempotency_keys} table: a served mutating
 * request — its canonical fingerprint and the entity it created.
 */
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKeyPk.class)
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "scope", nullable = false, length = 24)
    private String scope;

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 512)
    private String requestFingerprint;

    @Column(name = "entity_id", nullable = false, length = 40)
    private String entityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKeyEntity() {
    }

    public IdempotencyKeyEntity(IdempotencyKeyPk id, String requestFingerprint, String entityId,
                                Instant createdAt) {
        this.scope = id.getScope();
        this.idempotencyKey = id.getIdempotencyKey();
        this.requestFingerprint = requestFingerprint;
        this.entityId = entityId;
        this.createdAt = createdAt;
    }

    public String getScope() {
        return scope;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getEntityId() {
        return entityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
