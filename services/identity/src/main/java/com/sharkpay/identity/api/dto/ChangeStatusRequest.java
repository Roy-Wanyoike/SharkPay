package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /internal/v1/principals/{id}/status.
 */
public record ChangeStatusRequest(
        @JsonProperty("status")
        @NotBlank(message = "status must not be blank")
        String status) {
}
