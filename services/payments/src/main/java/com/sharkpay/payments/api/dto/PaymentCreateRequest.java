package com.sharkpay.payments.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * POST /payments request body (payments.yaml PaymentCreateRequest).
 * {@code rail} is an optional hint; {@code expires_in_seconds} defaults to
 * 900. Money arrives as integer minor units only.
 */
public record PaymentCreateRequest(

        @NotNull(message = "must be a positive integer")
        @Min(value = 1, message = "must be a positive integer")
        Long amount_minor,

        @NotBlank(message = "must be one of KES, USD, EUR, GBP, USDC, USDT")
        String currency,

        @NotBlank(message = "must match wal_[0-9A-Za-z]{20,}")
        @Pattern(regexp = "^wal_[0-9A-Za-z]{20,}$", message = "must match wal_[0-9A-Za-z]{20,}")
        String destination_wallet,

        String rail,

        @Size(max = 20, message = "must contain at most 20 entries")
        Map<String, String> metadata,

        @Min(value = 60, message = "must be at least 60")
        @Max(value = 86400, message = "must be at most 86400")
        Integer expires_in_seconds) {
}
