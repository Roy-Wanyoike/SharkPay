package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.identity.domain.KycRecord;
import com.sharkpay.identity.domain.KycStatus;
import com.sharkpay.identity.domain.KycTier;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * KYC decision record representation.
 */
public record KycRecordResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("principal_id") UUID principalId,
        @JsonProperty("tier") KycTier tier,
        @JsonProperty("status") KycStatus status,
        @JsonProperty("provider_ref") String providerRef,
        @JsonProperty("decided_at") OffsetDateTime decidedAt) {

    public static KycRecordResponse from(KycRecord record) {
        return new KycRecordResponse(
                record.id(),
                record.principalId(),
                record.tier(),
                record.status(),
                record.providerRef(),
                record.decidedAt());
    }
}
