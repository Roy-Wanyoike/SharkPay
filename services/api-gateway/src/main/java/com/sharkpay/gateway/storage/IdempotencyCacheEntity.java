package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.ports.IdempotencyCache;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code idempotency_cache} table: one served response
 * per (scope, idempotency key). Passthrough entries carry the relayed
 * upstream (status, body); native management entries carry the created
 * entity id instead — never a response body, so plaintext secrets cannot
 * end up at rest here.
 */
@Entity
@Table(name = "idempotency_cache")
@IdClass(IdempotencyCacheEntityId.class)
public class IdempotencyCacheEntity {

    @Id
    @Column(name = "scope", nullable = false, length = 64)
    private String scope;

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 512)
    private String requestFingerprint;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "entity_id", length = 40)
    private String entityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyCacheEntity() {
    }

    public IdempotencyCacheEntity(String scope, String idempotencyKey,
                                  String requestFingerprint, int statusCode, String responseBody,
                                  String entityId, Instant createdAt) {
        this.scope = scope;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.entityId = entityId;
        this.createdAt = createdAt;
    }

    public IdempotencyCache.CachedResponse toDomain() {
        return new IdempotencyCache.CachedResponse(requestFingerprint, statusCode, responseBody,
                entityId);
    }

    public static IdempotencyCacheEntity fromDomain(String scope, String idempotencyKey,
                                                    IdempotencyCache.CachedResponse response,
                                                    Instant createdAt) {
        return new IdempotencyCacheEntity(scope, idempotencyKey, response.requestFingerprint(),
                response.status(), response.body(), response.entityId(), createdAt);
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

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getEntityId() {
        return entityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "IdempotencyCacheEntity{scope=" + scope + ", key=" + idempotencyKey + "}";
    }
}
