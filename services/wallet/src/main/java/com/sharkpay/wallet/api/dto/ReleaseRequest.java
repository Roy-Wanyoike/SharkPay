package com.sharkpay.wallet.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /internal/holds/{id}/release} (Idempotency-Key header
 * required).
 */
public record ReleaseRequest(
        @Size(max = 512, message = "reason must be at most 512 characters") String reason) {
}
