package com.sharkpay.wallet.api.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /internal/holds/{id}/capture} (Idempotency-Key header
 * required). {@code amount_minor} omitted (or null) ⇒ capture the full
 * reserved amount; otherwise a partial capture whose remainder is released.
 */
public record CaptureRequest(
        @Positive(message = "amount_minor must be positive") Long amount_minor,
        @Size(max = 512, message = "reason must be at most 512 characters") String reason) {
}
