package com.sharkpay.gateway.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /sandbox/payments request — clearly separated simulated provider
 * surface (no real money, no persistence, deterministic scripted flow).
 */
public record SandboxPaymentCreateRequest(
        @NotNull @Min(1) Long amount_minor,
        @NotBlank String currency,
        @NotBlank String destination_wallet,
        @NotBlank String rail) {
}
