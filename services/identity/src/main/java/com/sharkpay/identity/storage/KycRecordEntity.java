package com.sharkpay.identity.storage;

import com.sharkpay.identity.domain.KycRecord;
import com.sharkpay.identity.domain.KycStatus;
import com.sharkpay.identity.domain.KycTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity for the {@code kyc_records} table (KYC decision audit log).
 */
@Entity
@Table(name = "kyc_records")
public class KycRecordEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "tier", nullable = false, length = 16)
    private String tier;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "provider_ref", length = 128)
    private String providerRef;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected KycRecordEntity() {
        // JPA
    }

    public static KycRecordEntity fromDomain(KycRecord record) {
        KycRecordEntity entity = new KycRecordEntity();
        entity.id = record.id();
        entity.principalId = record.principalId();
        entity.tier = record.tier().name();
        entity.status = record.status().name();
        entity.providerRef = record.providerRef();
        entity.decidedAt = record.decidedAt();
        entity.createdAt = record.createdAt();
        return entity;
    }

    public KycRecord toDomain() {
        return new KycRecord(
                id,
                principalId,
                KycTier.valueOf(tier),
                KycStatus.valueOf(status),
                providerRef,
                decidedAt,
                createdAt);
    }
}
