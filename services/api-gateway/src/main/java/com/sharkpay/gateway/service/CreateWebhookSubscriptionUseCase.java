package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.EventPattern;
import com.sharkpay.gateway.domain.WebhookSubscription;
import com.sharkpay.gateway.events.EventTypeCatalog;
import com.sharkpay.gateway.ports.Randomness;
import com.sharkpay.gateway.ports.WebhookSubscriptionRepository;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Register-webhook-endpoint use-case (contracts/openapi/v1/webhooks.yaml
 * createWebhookEndpoint): https-only URL (TLS required), event types from
 * the catalog (exact names or {@code *} globs), caller-supplied HMAC secret
 * (16..256 chars) returned in full only in the creation response.
 */
public final class CreateWebhookSubscriptionUseCase {

    private final WebhookSubscriptionRepository subscriptions;
    private final Randomness randomness;
    private final Clock clock;

    public CreateWebhookSubscriptionUseCase(WebhookSubscriptionRepository subscriptions,
                                            Randomness randomness, Clock clock) {
        this.subscriptions = Objects.requireNonNull(subscriptions,
                "webhookSubscriptionRepository is required");
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param url       https endpoint URL (422 http_url_required otherwise)
     * @param eventTypes catalog names or globs, at least one, unique
     * @param secret    HMAC-SHA256 signing secret ({@code whsec_...})
     */
    public WebhookSubscription create(UUID principal, String url, List<String> eventTypes,
                                      String secret) {
        Objects.requireNonNull(principal, "principal is required");
        Objects.requireNonNull(url, "url is required");
        Objects.requireNonNull(secret, "secret is required");
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new com.sharkpay.gateway.domain.InvalidEventTypesException(
                    "at least one event type is required");
        }
        Set<EventPattern> patterns = new LinkedHashSet<>();
        for (String eventType : eventTypes) {
            patterns.add(parse(eventType));
        }
        WebhookSubscription subscription = WebhookSubscription.active(randomness.webhookId(),
                principal, url, patterns, secret, clock.instant());
        return subscriptions.save(subscription);
    }

    /**
     * Exact names must be catalog names; anything with a {@code *} is a glob
     * and validated by {@link EventPattern} shape rules.
     */
    private static EventPattern parse(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new com.sharkpay.gateway.domain.InvalidEventTypesException(
                    "event type must not be blank");
        }
        if (eventType.indexOf('*') < 0 && EventTypeCatalog.fromPublicName(eventType).isEmpty()) {
            throw new com.sharkpay.gateway.domain.InvalidEventTypesException(
                    "unknown webhook event type: " + eventType);
        }
        return EventPattern.of(eventType);
    }
}
