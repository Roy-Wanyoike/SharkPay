package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /internal/v1/principals.
 */
public record CreatePrincipalRequest(
        @JsonProperty("type")
        @NotNull(message = "type must not be null")
        String type,

        @JsonProperty("owner_shark_id")
        String ownerSharkId) {
}
