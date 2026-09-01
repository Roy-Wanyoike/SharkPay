package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.KycTier;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Canonical principal representation (snake_case). Superset of the contract
 * fields {id, shark_id, type, status}: also carries owner_principal_id,
 * kyc_tier and created_at.
 */
public record PrincipalResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("shark_id") String sharkId,
        @JsonProperty("type") PrincipalType type,
        @JsonProperty("status") PrincipalStatus status,
        @JsonProperty("owner_principal_id") UUID ownerPrincipalId,
        @JsonProperty("kyc_tier") KycTier kycTier,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public static PrincipalResponse from(Principal principal) {
        return new PrincipalResponse(
                principal.id(),
                principal.sharkId().value(),
                principal.type(),
                principal.status(),
                principal.ownerPrincipalId(),
                principal.kycTier(),
                principal.createdAt());
    }
}
