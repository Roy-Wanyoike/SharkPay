package com.sharkpay.identity.storage;

import com.sharkpay.identity.ports.Clock;
import com.sharkpay.identity.ports.IdempotentRequest;
import com.sharkpay.identity.ports.IdempotencyStore;
import java.util.Optional;

/**
 * Adapter implementing the {@link IdempotencyStore} port on top of
 * {@link IdempotencyJpaRepository}.
 */
@org.springframework.stereotype.Repository
public class JpaIdempotencyStore implements IdempotencyStore {

    private final IdempotencyJpaRepository entities;
    private final Clock clock;

    public JpaIdempotencyStore(IdempotencyJpaRepository entities, Clock clock) {
        this.entities = entities;
        this.clock = clock;
    }

    @Override
    public Optional<IdempotentRequest> findByKey(String key) {
        return entities.findById(key).map(IdempotencyEntity::toDomain);
    }

    @Override
    public void save(IdempotentRequest request) {
        entities.save(IdempotencyEntity.fromDomain(request, clock.now()));
    }
}
