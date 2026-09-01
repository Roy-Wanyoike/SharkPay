package com.sharkpay.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /internal/wallets/{id}/freeze} and
 * {@code /internal/wallets/{id}/unfreeze}: the mandatory audit reason.
 */
public record ChangeStatusRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 512, message = "reason must be at most 512 characters") String reason) {
}
