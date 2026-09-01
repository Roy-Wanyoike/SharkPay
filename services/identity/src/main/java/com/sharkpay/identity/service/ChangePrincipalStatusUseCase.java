package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.ports.Clock;
import com.sharkpay.identity.ports.event.EventPublisher;
import com.sharkpay.identity.ports.PrincipalRepository;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Applies a principal status transition. CLOSED is terminal. Suspending a
 * principal resets the KYC tier to UNVERIFIED (re-verification) and emits
 * identity.kyc.tier.changed.v1 in addition to
 * identity.principal.status.changed.v1.
 */
public final class ChangePrincipalStatusUseCase {

    private final PrincipalRepository principalRepository;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    public ChangePrincipalStatusUseCase(PrincipalRepository principalRepository,
                                        EventPublisher eventPublisher,
                                        Clock clock) {
        this.principalRepository = principalRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public Principal execute(UUID principalId, PrincipalStatus newStatus) {
        Principal current = principalRepository.findById(principalId)
                .orElseThrow(() -> new NotFoundException("PRINCIPAL_NOT_FOUND",
                        "no principal with id " + principalId));
        OffsetDateTime now = clock.now();
        Principal updated = current.withStatus(newStatus, now);
        if (newStatus == PrincipalStatus.SUSPENDED && current.kycTier() != KycTier.UNVERIFIED) {
            updated = updated.resetKycTier(now);
        }
        Principal saved = principalRepository.save(updated);
        eventPublisher.publish(IdentityEvents.principalStatusChanged(current, saved, now));
        if (saved.kycTier() != current.kycTier()) {
            eventPublisher.publish(IdentityEvents.kycTierChanged(saved, current.kycTier(),
                    saved.kycTier(), IdentityEvents.REASON_SUSPENSION_RESET, null, now));
        }
        return saved;
    }
}
