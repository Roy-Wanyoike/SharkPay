package com.sharkpay.payments.storage;

import com.sharkpay.payments.ports.IdempotencyStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * JPA adapter for the idempotency store port (keys scoped by operation
 * type, unique on (scope, idempotency_key)). Component-scanned production
 * adapter.
 */
@Repository
public final class JpaIdempotencyStore implements IdempotencyStore {

    private final IdempotencyKeyJpaRepository jpa;

    public JpaIdempotencyStore(IdempotencyKeyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<StoredRequest> find(Scope scope, String idempotencyKey) {
        return jpa.findById(new IdempotencyKeyPk(scope.name(), idempotencyKey))
                .map(entity -> new StoredRequest(entity.getRequestFingerprint(),
                        entity.getEntityId()));
    }

    @Override
    public void put(Scope scope, String idempotencyKey, StoredRequest request) {
        try {
            jpa.saveAndFlush(new IdempotencyKeyEntity(
                    new IdempotencyKeyPk(scope.name(), idempotencyKey),
                    request.requestFingerprint(), request.entityId(), Instant.now()));
        } catch (DataIntegrityViolationException race) {
            // Two concurrent requests with the same key: the loser replays
            // the winner's record on its next attempt (find-first wins).
        }
    }

    @Override
    public void remove(Scope scope, String idempotencyKey) {
        jpa.deleteById(new IdempotencyKeyPk(scope.name(), idempotencyKey));
    }
}
