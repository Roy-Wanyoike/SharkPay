package com.sharkpay.identity.domain;

import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.ValidationException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identity principal: an individual, a business or an agent.
 *
 * <p>Invariants enforced by the canonical constructor:</p>
 * <ul>
 *   <li>Agents must reference an owner principal; non-agents must not.</li>
 *   <li>{@code createdAt <= updatedAt}.</li>
 * </ul>
 *
 * <p>State changes go through the wither methods, which validate the
 * transitions (see {@link PrincipalStatus} and {@link KycTier}).</p>
 */
public record Principal(
        UUID id,
        SharkId sharkId,
        PrincipalType type,
        UUID ownerPrincipalId,
        PrincipalStatus status,
        KycTier kycTier,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public Principal {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sharkId, "sharkId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(kycTier, "kycTier must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (type == PrincipalType.AGENT && ownerPrincipalId == null) {
            throw new ValidationException("AGENT_REQUIRES_OWNER",
                    "an AGENT principal must reference an owner principal");
        }
        if (type != PrincipalType.AGENT && ownerPrincipalId != null) {
            throw new ValidationException("NON_AGENT_MUST_NOT_HAVE_OWNER",
                    "owner_principal_id is only valid for AGENT principals");
        }
        if (createdAt.isAfter(updatedAt)) {
            throw new ValidationException("INVALID_TIMESTAMPS",
                    "createdAt must not be after updatedAt");
        }
    }

    /**
     * Applies a lifecycle status transition. CLOSED is terminal and
     * re-entering the same status is rejected.
     */
    public Principal withStatus(PrincipalStatus newStatus, OffsetDateTime at) {
        if (!status.canTransitionTo(newStatus)) {
            throw new ConflictException("ILLEGAL_STATUS_TRANSITION",
                    "illegal principal status transition " + status + " -> " + newStatus
                            + (status == PrincipalStatus.CLOSED ? " (CLOSED is terminal)" : ""));
        }
        return new Principal(id, sharkId, type, ownerPrincipalId, newStatus, kycTier, createdAt, at);
    }

    /**
     * Applies a single forward KYC tier upgrade (UNVERIFIED -&gt; LIMITED -&gt; FULL).
     */
    public Principal advanceKycTier(KycTier target, OffsetDateTime at) {
        if (!kycTier.canAdvanceTo(target)) {
            throw new ConflictException("ILLEGAL_TIER_TRANSITION",
                    "illegal KYC tier transition " + kycTier + " -> " + target
                            + " (forward single-step only: UNVERIFIED -> LIMITED -> FULL)");
        }
        return new Principal(id, sharkId, type, ownerPrincipalId, status, target, createdAt, at);
    }

    /**
     * Re-verification reset to UNVERIFIED. Only sanctioned while the principal
     * is SUSPENDED (see docs/STATE-MACHINES.md §5).
     */
    public Principal resetKycTier(OffsetDateTime at) {
        if (status != PrincipalStatus.SUSPENDED) {
            throw new ConflictException("TIER_RESET_REQUIRES_SUSPENSION",
                    "the KYC tier may only be reset to UNVERIFIED while the principal is SUSPENDED");
        }
        return new Principal(id, sharkId, type, ownerPrincipalId, status, KycTier.UNVERIFIED, createdAt, at);
    }
}
