package com.sharkpay.payments.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /internal/payments/{id}/provider-result request body: the provider
 * transfer status ingested from callbacks / polling
 * ({@link com.sharkpay.payments.ports.ProviderGatewayPort.TransferStatus}
 * wire names).
 */
public record ProviderResultRequest(

        @NotBlank(message = "must be one of PENDING, PROCESSING, SUCCEEDED, FAILED, RETURNED, UNKNOWN")
        String status,

        String provider_ref,

        String reason) {

    /** The rail-agnostic statuses the internal endpoint accepts. */
    public static final java.util.Set<String> KNOWN_STATUSES = java.util.Set.of(
            "PENDING", "PROCESSING", "SUCCEEDED", "FAILED", "RETURNED", "UNKNOWN");
}
