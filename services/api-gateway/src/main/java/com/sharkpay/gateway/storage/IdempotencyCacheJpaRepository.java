package com.sharkpay.gateway.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link IdempotencyCacheEntity}.
 */
public interface IdempotencyCacheJpaRepository
        extends JpaRepository<IdempotencyCacheEntity, IdempotencyCacheEntityId> {

    Optional<IdempotencyCacheEntity> findByScopeAndIdempotencyKey(String scope,
                                                                  String idempotencyKey);
}
