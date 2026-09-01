package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body of POST /internal/v1/principals/{id}/devices.
 */
public record RegisterDeviceRequest(
        @JsonProperty("fingerprint")
        @NotBlank(message = "fingerprint must not be blank")
        @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "fingerprint must be a 64-character sha-256 hex string")
        String fingerprint) {
}
