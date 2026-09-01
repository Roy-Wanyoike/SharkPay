package com.sharkpay.fx.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link IdempotencyKeyEntity}. */
public interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    Optional<IdempotencyKeyEntity> findByIdempotencyKey(String idempotencyKey);

    /** Derived delete; a no-op when the key was never stored. */
    void deleteByIdempotencyKey(String idempotencyKey);
}
