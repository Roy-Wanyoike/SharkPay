package com.sharkpay.reconciliation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Body of {@code POST /internal/recon/runs}: trigger a recon run.
 */
public record TriggerRunRequest(
        @NotBlank(message = "provider is required") @Size(max = 64) String provider,
        @NotNull(message = "from is required") Instant from,
        @NotNull(message = "to is required") Instant to) {
}
