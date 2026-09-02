package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.ApiKeyStatus;
import com.sharkpay.gateway.domain.Scope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA entity for the {@code api_keys} table. Scopes are stored as the
 * sorted, comma-joined wire names ({@code payments:read,payments:write}).
 * The secret hash is a fixed 64-char lowercase hex column — the plaintext
 * {@code sp_live_...} value never touches storage.
 */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "scopes", nullable = false, length = 512)
    private String scopes;

    @Column(name = "status", nullable = false, length = 8)
    private String status;

    @Column(name = "grace_expires_at")
    private Instant graceExpiresAt;

    @Column(name = "rpm_limit", nullable = false)
    private int rpmLimit;

    @Column(name = "monthly_limit", nullable = false)
    private long monthlyLimit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApiKeyEntity() {
    }

    private ApiKeyEntity(String id, UUID principalId, String secretHash, String scopes,
                         String status, Instant graceExpiresAt, int rpmLimit, long monthlyLimit,
                         Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.principalId = principalId;
        this.secretHash = secretHash;
        this.scopes = scopes;
        this.status = status;
        this.graceExpiresAt = graceExpiresAt;
        this.rpmLimit = rpmLimit;
        this.monthlyLimit = monthlyLimit;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ApiKeyEntity fromDomain(ApiKey key) {
        // scopes are stored sorted for deterministic rows (hash-order of the
        // domain set must never leak into storage)
        String scopes = Scope.toWireNames(key.scopes()).stream().sorted()
                .reduce((left, right) -> left + "," + right).orElseThrow();
        return new ApiKeyEntity(key.id(), key.principalId(), key.secretHash(), scopes,
                key.status().name(), key.graceExpiresAt(), key.rpmLimit(), key.monthlyLimit(),
                key.createdAt(), key.updatedAt());
    }

    public ApiKey toDomain() {
        Set<Scope> parsed = new LinkedHashSet<>();
        for (String wireName : scopes.split(",")) {
            parsed.add(Scope.parse(wireName));
        }
        return new ApiKey(id, principalId, secretHash, Set.copyOf(parsed),
                ApiKeyStatus.valueOf(status), graceExpiresAt, rpmLimit, monthlyLimit, createdAt,
                updatedAt);
    }

    /** Refreshes the mutable lifecycle fields from the domain object. */
    public void applyDomain(ApiKey key) {
        this.status = key.status().name();
        this.graceExpiresAt = key.graceExpiresAt();
        this.updatedAt = key.updatedAt();
    }

    public String getId() {
        return id;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public String getSecretHash() {
        return secretHash;
    }

    String getScopes() {
        return scopes;
    }

    public String getStatus() {
        return status;
    }

    public Instant getGraceExpiresAt() {
        return graceExpiresAt;
    }

    public int getRpmLimit() {
        return rpmLimit;
    }

    public long getMonthlyLimit() {
        return monthlyLimit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "ApiKeyEntity{id=" + id + ", status=" + status + ", scopes=" + scopes + "}";
    }
}
