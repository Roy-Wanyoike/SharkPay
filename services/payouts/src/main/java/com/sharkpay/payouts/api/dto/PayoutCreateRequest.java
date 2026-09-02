package com.sharkpay.payouts.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * POST /payouts request body (contracts/openapi/v1/payouts.yaml
 * PayoutCreateRequest). {@code rail} is an optional hint that must be
 * compatible with the destination type; {@code expires_in_seconds} defaults
 * to 900 (range 60..86400).
 */
public record PayoutCreateRequest(
        @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^wal_[0-9A-Za-z]{20,}$",
                message = "source_wallet must match ^wal_[0-9A-Za-z]{20,}$") String source_wallet,
        @NotNull @Positive(message = "amount_minor must be a positive integer") long amount_minor,
        @NotBlank @Size(min = 3, max = 4, message = "currency must be a supported code")
        String currency,
        @NotNull DestinationJson destination,
        String rail,
        @Size(max = 20, message = "metadata supports at most 20 entries") Map<String, String> metadata,
        @jakarta.validation.constraints.Min(value = 60,
                message = "expires_in_seconds must be at least 60")
        @jakarta.validation.constraints.Max(value = 86400,
                message = "expires_in_seconds must be at most 86400")
        Integer expires_in_seconds) {
}
