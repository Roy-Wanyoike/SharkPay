package com.sharkpay.identity.domain;

import com.sharkpay.identity.domain.exception.ValidationException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable KYC decision record — the audit log of verification decisions
 * received from the KYC provider. APPROVED decisions move the principal's
 * tier forward; PENDING/REJECTED decisions never change the tier.
 *
 * <p>{@code decidedAt} is null while the decision is PENDING and mandatory
 * otherwise.</p>
 */
public record KycRecord(
        UUID id,
        UUID principalId,
        KycTier tier,
        KycStatus status,
        String providerRef,
        OffsetDateTime decidedAt,
        OffsetDateTime createdAt) {

    public KycRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(principalId, "principalId must not be null");
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (status == KycStatus.PENDING && decidedAt != null) {
            throw new ValidationException("PENDING_HAS_NO_DECISION_TIME",
                    "a PENDING KYC decision must not carry decidedAt");
        }
        if (status != KycStatus.PENDING && decidedAt == null) {
            throw new ValidationException("DECIDED_REQUIRES_TIME",
                    "an APPROVED or REJECTED decision must carry decidedAt");
        }
        if (providerRef != null && providerRef.isBlank()) {
            throw new ValidationException("INVALID_PROVIDER_REF",
                    "providerRef must be non-blank when present");
        }
    }
}
