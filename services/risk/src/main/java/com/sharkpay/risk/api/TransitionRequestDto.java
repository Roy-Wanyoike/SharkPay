package com.sharkpay.risk.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /internal/v1/risk/cases/{id}/transitions} body. Status must be
 * a legal target of the case's current state; {@code resolution} is only
 * valid when closing (defaults to {@code cleared}).
 */
public record TransitionRequestDto(
        @JsonProperty("status") @NotBlank String status,
        @JsonProperty("actor") @NotBlank String actor,
        @JsonProperty("resolution") String resolution) {
}
