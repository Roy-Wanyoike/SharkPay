package com.sharkpay.fx.storage;

import com.sharkpay.fx.ports.StoredRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping of the {@code idempotency_keys} table (V1__fx_init.sql):
 * client Idempotency-Key &#8594; (request fingerprint, conversion id).
 * Unique on {@code idempotency_key} — the storage race between two
 * concurrent same-key requests surfaces as a constraint violation the
 * adapter swallows (find-first-wins, wallet-service convention).
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    public UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    public String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 256)
    public String requestFingerprint;

    @Column(name = "conversion_id", nullable = false, length = 40)
    public String conversionId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** New entity for a stored request under a key (fresh surrogate id). */
    public static IdempotencyKeyEntity of(String idempotencyKey, StoredRequest request, Instant now) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(now, "now is required");
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
        entity.id = UUID.randomUUID();
        entity.idempotencyKey = idempotencyKey;
        entity.requestFingerprint = request.requestFingerprint();
        entity.conversionId = request.conversionId();
        entity.createdAt = now;
        return entity;
    }

    /** Maps to the port record. */
    public StoredRequest toStoredRequest() {
        return new StoredRequest(requestFingerprint, conversionId);
    }
}
