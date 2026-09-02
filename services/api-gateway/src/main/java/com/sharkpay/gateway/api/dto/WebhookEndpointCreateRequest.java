package com.sharkpay.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /v1/webhook-endpoints request
 * (webhooks.yaml WebhookEndpointCreateRequest): https-only URL, catalog
 * event names (or {@code *} globs — gateway extension), caller-supplied
 * HMAC secret.
 */
public record WebhookEndpointCreateRequest(
        @NotBlank String url,
        @NotEmpty List<String> events,
        @NotBlank @Size(min = 16, max = 256) String secret) {
}
