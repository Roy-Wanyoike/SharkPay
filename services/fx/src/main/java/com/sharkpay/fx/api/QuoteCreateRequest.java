package com.sharkpay.fx.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * POST /fx/quotes request body (contracts/openapi/v1/fx.yaml
 * QuoteCreateRequest).
 */
public record QuoteCreateRequest(
        @Positive(message = "must be a positive integer") long amount_minor,
        @NotBlank(message = "is required") String base_currency,
        @NotBlank(message = "is required") String quote_currency,
        @Min(value = 5, message = "must be between 5 and 3600 seconds") @Max(value = 3600, message = "must be between 5 and 3600 seconds") Integer expires_in_seconds) {
}
