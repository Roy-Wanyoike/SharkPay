package com.sharkpay.identity.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link IdempotencyEntity}; key lookups use
 * {@code findById} (the key is the primary key).
 */
@Repository
public interface IdempotencyJpaRepository extends JpaRepository<IdempotencyEntity, String> {
}
