package com.sharkpay.payouts.storage;

import com.sharkpay.payouts.ports.IdempotencyStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * JPA adapter for the idempotency store port (unique on
 * (scope, idempotency_key)). Two concurrent requests with the same key
 * race on the unique constraint: the loser's put is swallowed and its next
 * attempt replays the winner's record (find-first wins — the same
 * convention as the wallet service's adapter).
 */
@Repository
public final class JpaIdempotencyStore implements IdempotencyStore {

    private final IdempotencyKeyJpaRepository jpa;

    public JpaIdempotencyStore(IdempotencyKeyJpaRepository jpa) {
        this.jpa = Objects.requireNonNull(jpa, "idempotencyKeyJpaRepository is required");
    }

    @Override
    public Optional<StoredRequest> find(Scope scope, String idempotencyKey) {
        return jpa.findByScopeAndIdempotencyKey(scope.name(), idempotencyKey)
                .map(IdempotencyKeyEntity::toStoredRequest);
    }

    @Override
    public void put(Scope scope, String idempotencyKey, StoredRequest request) {
        try {
            jpa.saveAndFlush(IdempotencyKeyEntity.of(scope.name(), idempotencyKey, request,
                    Instant.now()));
        } catch (DataIntegrityViolationException race) {
            // same-key concurrent winner already stored: the loser replays
            // the winner's record on its next find() — no double effect
        }
    }

    @Override
    public void remove(Scope scope, String idempotencyKey) {
        try {
            jpa.deleteByScopeAndIdempotencyKey(scope.name(), idempotencyKey);
        } catch (EmptyResultDataAccessException absent) {
            // removing a never-stored key is a no-op
        }
    }
}
