package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.ports.IdempotencyCache;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.Optional;

/**
 * JPA adapter for the idempotency cache port.
 */
@Repository
public final class JpaIdempotencyCache implements IdempotencyCache {

    private final IdempotencyCacheJpaRepository jpa;
    private final Clock clock;

    public JpaIdempotencyCache(IdempotencyCacheJpaRepository jpa, Clock clock) {
        this.jpa = jpa;
        this.clock = clock;
    }

    @Override
    public Optional<CachedResponse> find(String scope, String idempotencyKey) {
        return jpa.findById(new IdempotencyCacheEntityId(scope, idempotencyKey))
                .map(IdempotencyCacheEntity::toDomain);
    }

    @Override
    public void put(String scope, String idempotencyKey, CachedResponse response) {
        jpa.save(IdempotencyCacheEntity.fromDomain(scope, idempotencyKey, response,
                clock.instant()));
    }
}
