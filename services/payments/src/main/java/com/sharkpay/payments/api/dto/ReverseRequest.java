package com.sharkpay.payments.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * POST /internal/payments/{id}/reverse request body: the reversal amount in
 * minor units (defaults to the full captured amount when null) plus an audit
 * reason.
 */
public record ReverseRequest(

        @Min(value = 1, message = "must be a positive integer")
        Long amount_minor,

        @NotNull(message = "must not be null (use \"\" for no reason)")
        String reason) {
}
