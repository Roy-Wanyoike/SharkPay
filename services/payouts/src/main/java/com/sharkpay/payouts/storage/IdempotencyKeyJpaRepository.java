package com.sharkpay.payouts.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data surface of the {@code idempotency_keys} table. */
public interface IdempotencyKeyJpaRepository
        extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyPk> {

    Optional<IdempotencyKeyEntity> findByScopeAndIdempotencyKey(String scope, String idempotencyKey);

    void deleteByScopeAndIdempotencyKey(String scope, String idempotencyKey);
}
