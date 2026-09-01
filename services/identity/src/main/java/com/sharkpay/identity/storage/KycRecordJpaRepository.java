package com.sharkpay.identity.storage;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link KycRecordEntity}.
 */
@Repository
public interface KycRecordJpaRepository extends JpaRepository<KycRecordEntity, UUID> {

    List<KycRecordEntity> findByPrincipalId(UUID principalId);
}
