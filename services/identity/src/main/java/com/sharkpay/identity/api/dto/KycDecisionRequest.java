package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /internal/v1/principals/{id}/kyc.
 */
public record KycDecisionRequest(
        @JsonProperty("tier")
        @NotNull(message = "tier must not be null")
        String tier,

        @JsonProperty("status")
        @NotNull(message = "status must not be null")
        String status,

        @JsonProperty("provider_ref")
        String providerRef) {
}
