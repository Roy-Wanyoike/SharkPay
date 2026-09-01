package com.sharkpay.identity.storage;

import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity for the {@code principals} table (see
 * db/migration/V1__identity_init.sql). Mapping to/from the domain model is
 * done with static factories so it is directly unit-testable.
 */
@Entity
@Table(name = "principals")
public class PrincipalEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "shark_id", nullable = false, unique = true, length = 16)
    private String sharkId;

    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "owner_principal_id")
    private UUID ownerPrincipalId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "kyc_tier", nullable = false, length = 16)
    private String kycTier;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PrincipalEntity() {
        // JPA
    }

    public static PrincipalEntity fromDomain(Principal principal) {
        PrincipalEntity entity = new PrincipalEntity();
        entity.id = principal.id();
        entity.sharkId = principal.sharkId().value();
        entity.type = principal.type().name();
        entity.ownerPrincipalId = principal.ownerPrincipalId();
        entity.status = principal.status().name();
        entity.kycTier = principal.kycTier().name();
        entity.createdAt = principal.createdAt();
        entity.updatedAt = principal.updatedAt();
        return entity;
    }

    public Principal toDomain() {
        return new Principal(
                id,
                SharkId.of(sharkId),
                PrincipalType.valueOf(type),
                ownerPrincipalId,
                PrincipalStatus.valueOf(status),
                KycTier.valueOf(kycTier),
                createdAt,
                updatedAt);
    }
}
