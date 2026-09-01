package com.sharkpay.identity.storage;

import com.sharkpay.identity.ports.IdempotentRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity for the {@code idempotency_keys} table: the Idempotency-Key of
 * a create-principal request, the sha-256 fingerprint of its canonical body
 * and the id of the principal it created.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyEntity {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyEntity() {
        // JPA
    }

    public static IdempotencyEntity fromDomain(IdempotentRequest request, OffsetDateTime createdAt) {
        IdempotencyEntity entity = new IdempotencyEntity();
        entity.idempotencyKey = request.key();
        entity.requestFingerprint = request.requestFingerprint();
        entity.principalId = request.principalId();
        entity.createdAt = createdAt;
        return entity;
    }

    public IdempotentRequest toDomain() {
        return new IdempotentRequest(idempotencyKey, requestFingerprint, principalId);
    }
}
