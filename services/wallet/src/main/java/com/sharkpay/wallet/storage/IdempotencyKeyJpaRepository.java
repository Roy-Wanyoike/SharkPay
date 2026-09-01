package com.sharkpay.wallet.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link IdempotencyKeyEntity} (keys scoped by
 * operation type).
 */
public interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyPk> {
}
