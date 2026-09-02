package com.sharkpay.payouts.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /internal/payouts/{id}/provider-result body: a provider callback or
 * poll outcome ingested by the ops/provider adapter. {@code provider_ref}
 * is required once the rail accepted the submission; a RETURNED result
 * carries the returned amount (defaults to the full payout amount), its
 * currency (must match the payout) and the provider's own return
 * reference (the double-return dedupe key).
 */
public record ProviderResultRequest(
        @NotBlank String status,
        String provider_ref,
        String reason,
        Long returned_amount_minor,
        String returned_currency,
        String provider_return_ref) {
}
