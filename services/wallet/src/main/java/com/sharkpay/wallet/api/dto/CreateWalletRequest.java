package com.sharkpay.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of {@code POST /internal/wallets} (Idempotency-Key header required).
 */
public record CreateWalletRequest(
        @NotNull(message = "principal_id is required") UUID principal_id,
        @NotBlank(message = "currency is required") String currency) {
}
