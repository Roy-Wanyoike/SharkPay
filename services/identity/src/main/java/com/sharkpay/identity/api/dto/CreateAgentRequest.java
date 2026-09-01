package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /internal/v1/agents.
 */
public record CreateAgentRequest(
        @JsonProperty("owner_shark_id")
        @NotBlank(message = "owner_shark_id must not be blank")
        String ownerSharkId) {
}
