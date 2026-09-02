package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.ports.ApiKeyRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for the API key repository port: delegation + entity mapping
 * (no business logic — the domain owns the rules). Component-scanned
 * production adapter (mirrors the wallet service's storage package).
 */
@Repository
public final class JpaApiKeyRepository implements ApiKeyRepository {

    private final ApiKeyJpaRepository jpa;

    public JpaApiKeyRepository(ApiKeyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ApiKey save(ApiKey key) {
        return jpa.findById(key.id())
                .map(entity -> {
                    entity.applyDomain(key);
                    return jpa.save(entity).toDomain();
                })
                .orElseGet(() -> jpa.save(ApiKeyEntity.fromDomain(key)).toDomain());
    }

    @Override
    public Optional<ApiKey> findById(String id) {
        return jpa.findById(id).map(ApiKeyEntity::toDomain);
    }

    @Override
    public Optional<ApiKey> findByHash(String secretHash) {
        return jpa.findBySecretHash(secretHash).map(ApiKeyEntity::toDomain);
    }

    @Override
    public List<ApiKey> listByPrincipal(UUID principalId, int limit, String cursor) {
        return jpa.findByPrincipalIdOrderByIdAsc(principalId).stream()
                .filter(entity -> cursor == null || entity.getId().compareTo(cursor) > 0)
                .limit(Math.max(0, limit))
                .map(ApiKeyEntity::toDomain)
                .toList();
    }
}
