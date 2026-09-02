package com.sharkpay.gateway.api.dto;

import com.sharkpay.gateway.domain.WebhookSubscription;

import java.time.Instant;
import java.util.List;

/**
 * Webhook endpoint JSON, contract-exact per
 * contracts/openapi/v1/webhooks.yaml {@code WebhookEndpoint}
 * (additionalProperties: false — no extra fields). The signing secret is
 * returned in full only in the creation response; everywhere else it is
 * the fixed redaction {@value #REDACTED_SECRET}.
 *
 * <p>{@code state} extends the contract enum {@code active|dead} with the
 * gateway's operator {@code paused} (soft-{@code deleted} endpoints are
 * 404 and never rendered) — documented as an append-only contract
 * addition for the integrator.</p>
 */
public record WebhookEndpointJson(String id, String url, List<String> events, String state,
                                  String secret, Instant created_at, Instant updated_at) {

    /** The redaction shown wherever the secret must not be repeated. */
    public static final String REDACTED_SECRET = "whsec_redacted";

    /** The one rendering that carries the secret in full (creation only). */
    public static WebhookEndpointJson withSecret(WebhookSubscription subscription) {
        return of(subscription, subscription.signingSecret());
    }

    /** The redacted rendering (get/list/replay). */
    public static WebhookEndpointJson redacted(WebhookSubscription subscription) {
        return of(subscription, REDACTED_SECRET);
    }

    private static WebhookEndpointJson of(WebhookSubscription subscription, String secret) {
        return new WebhookEndpointJson(subscription.id(), subscription.url(),
                subscription.eventPatterns().stream().map(pattern -> pattern.pattern())
                        .sorted().toList(),
                subscription.state().wireName(), secret, subscription.createdAt(),
                subscription.updatedAt());
    }
}
