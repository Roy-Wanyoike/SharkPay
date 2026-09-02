package com.sharkpay.payouts.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * POST /transfers request body (contracts/openapi/v1/transfers.yaml
 * TransferCreateRequest). Money arrives as flat minor units + currency —
 * the canonical input shape.
 */
public record TransferCreateRequest(
        @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^wal_[0-9A-Za-z]{20,}$",
                message = "source_wallet must match ^wal_[0-9A-Za-z]{20,}$") String source_wallet,
        @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^wal_[0-9A-Za-z]{20,}$",
                message = "destination_wallet must match ^wal_[0-9A-Za-z]{20,}$")
        String destination_wallet,
        @NotNull @Positive(message = "amount_minor must be a positive integer") long amount_minor,
        @NotBlank @Size(min = 3, max = 4, message = "currency must be a supported code")
        String currency,
        @Size(max = 20, message = "metadata supports at most 20 entries") Map<String, String> metadata) {
}
