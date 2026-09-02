package com.sharkpay.reconciliation.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository behind {@link JpaIdempotencyStore}.
 */
@Repository
public interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyPk> {
}
