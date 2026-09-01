package com.sharkpay.identity.storage;

import com.sharkpay.identity.domain.KycRecord;
import com.sharkpay.identity.ports.KycRepository;
import java.util.List;
import java.util.UUID;

/**
 * Adapter implementing the {@link KycRepository} port on top of
 * {@link KycRecordJpaRepository}.
 */
@org.springframework.stereotype.Repository
public class JpaKycRepository implements KycRepository {

    private final KycRecordJpaRepository entities;

    public JpaKycRepository(KycRecordJpaRepository entities) {
        this.entities = entities;
    }

    @Override
    public KycRecord save(KycRecord record) {
        return entities.save(KycRecordEntity.fromDomain(record)).toDomain();
    }

    @Override
    public List<KycRecord> findByPrincipalId(UUID principalId) {
        return entities.findByPrincipalId(principalId).stream()
                .map(KycRecordEntity::toDomain)
                .toList();
    }
}
