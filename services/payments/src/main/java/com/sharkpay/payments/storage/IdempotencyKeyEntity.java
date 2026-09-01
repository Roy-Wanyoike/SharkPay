package com.sharkpay.payments.storage;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code idempotency_keys} table: a served mutating
 * request — its canonical fingerprint and the payment it served.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

    @EmbeddedId
    private IdempotencyKeyPk id;

    @Column(name = "request_fingerprint", nullable = false, length = 256)
    private String requestFingerprint;

    @Column(name = "entity_id", nullable = false, length = 40)
    private String entityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKeyEntity() {
    }

    public IdempotencyKeyEntity(IdempotencyKeyPk id, String requestFingerprint, String entityId,
                                Instant createdAt) {
        this.id = id;
        this.requestFingerprint = requestFingerprint;
        this.entityId = entityId;
        this.createdAt = createdAt;
    }

    public IdempotencyKeyPk getId() {
        return id;
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
