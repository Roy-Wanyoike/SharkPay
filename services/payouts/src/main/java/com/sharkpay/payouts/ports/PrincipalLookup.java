package com.sharkpay.payouts.ports;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-driven port to the identity service's principal lookup. The
 * payouts domain needs the owner's status (ACTIVE / otherwise) and KYC tier
 * at create time — payouts require at least LIMITED KYC (payouts.yaml's
 * {@code kyc_required} rejection).
 *
 * <p>Production adapter (REST against identity) lands at integration; local
 * tests run the in-tree fake (ADR 003 §3).</p>
 */
public interface PrincipalLookup {

    Optional<PrincipalSnapshot> findById(UUID principalId);

    /** Principal status (identity service states). */
    enum PrincipalStatus { ACTIVE, SUSPENDED, CLOSED }

    /** KYC tier (docs/STATE-MACHINES.md §5 — upgrades only). */
    enum KycTier { UNVERIFIED, LIMITED, FULL }

    /** A principal's read-side snapshot. */
    record PrincipalSnapshot(UUID principalId, PrincipalStatus status, KycTier kycTier) {

        public PrincipalSnapshot {
            Objects.requireNonNull(principalId, "principalId is required");
            Objects.requireNonNull(status, "status is required");
            Objects.requireNonNull(kycTier, "kycTier is required");
        }

        public boolean isActive() {
            return status == PrincipalStatus.ACTIVE;
        }

        public boolean canPayout() {
            return isActive() && kycTier != KycTier.UNVERIFIED;
        }
    }
}
