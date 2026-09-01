package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.ports.event.CloudEvent;
import com.sharkpay.identity.ports.event.EventIds;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory for the CloudEvents this service publishes. Event types are
 * registered in contracts/events/identity.v1.json; payloads are snake_case
 * per the repo-wide contract style.
 */
public final class IdentityEvents {

    public static final String SOURCE = "sharkpay/identity";

    public static final String PRINCIPAL_CREATED = "identity.principal.created.v1";
    public static final String PRINCIPAL_STATUS_CHANGED = "identity.principal.status.changed.v1";
    public static final String KYC_TIER_CHANGED = "identity.kyc.tier.changed.v1";

    /** Reason codes carried by identity.kyc.tier.changed.v1. */
    public static final String REASON_KYC_DECISION = "kyc_decision";
    public static final String REASON_SUSPENSION_RESET = "suspension_reset";

    private IdentityEvents() {
    }

    public static CloudEvent principalCreated(Principal principal, OffsetDateTime at) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("principal_id", principal.id().toString());
        data.put("shark_id", principal.sharkId().value());
        data.put("type", principal.type().name());
        data.put("status", principal.status().name());
        if (principal.ownerPrincipalId() != null) {
            data.put("owner_principal_id", principal.ownerPrincipalId().toString());
        }
        data.put("kyc_tier", principal.kycTier().name());
        return CloudEvent.of(PRINCIPAL_CREATED, SOURCE, EventIds.uuidV7().toString(), at, data);
    }

    public static CloudEvent principalStatusChanged(Principal before, Principal after, OffsetDateTime at) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("principal_id", after.id().toString());
        data.put("shark_id", after.sharkId().value());
        data.put("from_status", before.status().name());
        data.put("to_status", after.status().name());
        return CloudEvent.of(PRINCIPAL_STATUS_CHANGED, SOURCE, EventIds.uuidV7().toString(), at, data);
    }

    public static CloudEvent kycTierChanged(Principal principal, KycTier from, KycTier to,
                                            String reason, String providerRef, OffsetDateTime at) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("principal_id", principal.id().toString());
        data.put("shark_id", principal.sharkId().value());
        data.put("from_tier", from.name());
        data.put("to_tier", to.name());
        data.put("reason", reason);
        if (providerRef != null) {
            data.put("provider_ref", providerRef);
        }
        return CloudEvent.of(KYC_TIER_CHANGED, SOURCE, EventIds.uuidV7().toString(), at, data);
    }
}
