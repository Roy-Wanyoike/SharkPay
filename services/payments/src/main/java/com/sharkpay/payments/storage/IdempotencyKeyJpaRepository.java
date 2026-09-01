package com.sharkpay.payments.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link IdempotencyKeyEntity}
 * (idempotency_keys, unique on (scope, idempotency_key)).
 */
public interface IdempotencyKeyJpaRepository
        extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyPk> {
}
