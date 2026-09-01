package com.sharkpay.wallet.api.dto;

import com.sharkpay.wallet.domain.Source;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /internal/wallets/{id}/holds} (Idempotency-Key header
 * required): an explicit money amount (minor units + currency — the
 * currency must match the wallet's, else 422 currency_mismatch).
 */
public record PlaceHoldRequest(
        @Positive(message = "amount_minor must be positive") long amount_minor,
        @NotNull(message = "currency is required") String currency,
        @NotNull(message = "source is required") Source source,
        @NotNull(message = "source_ref is required") UUID source_ref,
        @Size(max = 512, message = "reason must be at most 512 characters") String reason) {
}
