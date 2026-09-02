package com.sharkpay.gateway.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link QuotaBucketEntity}.
 */
public interface QuotaBucketJpaRepository extends JpaRepository<QuotaBucketEntity, QuotaBucketId> {
}
