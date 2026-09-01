package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.KycRecord;
import com.sharkpay.identity.domain.KycStatus;
import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.ports.Clock;
import com.sharkpay.identity.ports.event.EventPublisher;
import com.sharkpay.identity.ports.KycRepository;
import com.sharkpay.identity.ports.PrincipalRepository;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Records a KYC provider decision and, when APPROVED, advances the
 * principal's tier one legal forward step (UNVERIFIED -&gt; LIMITED -&gt; FULL),
 * publishing identity.kyc.tier.changed.v1. PENDING and REJECTED decisions are
 * recorded for audit only and never change the tier.
 */
public final class VerifyKycUseCase {

    /** The stored decision plus the (possibly advanced) principal. */
    public record Result(Principal principal, KycRecord record) {
    }

    private final PrincipalRepository principalRepository;
    private final KycRepository kycRepository;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    public VerifyKycUseCase(PrincipalRepository principalRepository,
                            KycRepository kycRepository,
                            EventPublisher eventPublisher,
                            Clock clock) {
        this.principalRepository = principalRepository;
        this.kycRepository = kycRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public Result execute(UUID principalId, KycTier targetTier, KycStatus decision, String providerRef) {
        Principal principal = principalRepository.findById(principalId)
                .orElseThrow(() -> new NotFoundException("PRINCIPAL_NOT_FOUND",
                        "no principal with id " + principalId));
        if (principal.status() == PrincipalStatus.CLOSED) {
            throw new ConflictException("PRINCIPAL_CLOSED",
                    "no KYC decisions may be recorded for a CLOSED principal");
        }
        OffsetDateTime now = clock.now();
        OffsetDateTime decidedAt = decision == KycStatus.PENDING ? null : now;
        KycRecord record = new KycRecord(
                UUID.randomUUID(), principalId, targetTier, decision, providerRef, decidedAt, now);
        Principal updated = principal;
        if (decision == KycStatus.APPROVED) {
            updated = principal.advanceKycTier(targetTier, now);
            principalRepository.save(updated);
        }
        KycRecord saved = kycRepository.save(record);
        if (updated.kycTier() != principal.kycTier()) {
            eventPublisher.publish(IdentityEvents.kycTierChanged(updated, principal.kycTier(),
                    updated.kycTier(), IdentityEvents.REASON_KYC_DECISION, providerRef, now));
        }
        return new Result(updated, saved);
    }
}
