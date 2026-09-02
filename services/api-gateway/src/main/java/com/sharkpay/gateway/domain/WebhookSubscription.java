package com.sharkpay.gateway.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A registered webhook endpoint (contracts/openapi/v1/webhooks.yaml
 * {@code WebhookEndpoint}, extended with the gateway's glob event patterns
 * and pause/resume).
 *
 * <p>Endpoint URLs must be {@code https://} (TLS required — 422
 * {@code http_url_required} otherwise). Event types are exact catalog names
 * or {@code *} glob patterns ({@code payment.*}). The signing secret is
 * supplied by the caller at registration, used for HMAC-SHA256 delivery
 * signatures, returned in full only in the creation response.</p>
 *
 * @param id                        {@code wh_...}
 * @param principalId               owning principal (registrations are scoped to the caller)
 * @param url                       https-only delivery URL
 * @param eventPatterns             event-type patterns (exact names or globs)
 * @param signingSecret             HMAC secret (16..256 chars, {@code whsec_...} convention)
 * @param state                     lifecycle
 * @param consecutiveDeadDeliveries auto-pause counter (see {@link #recordDeadDelivery()})
 */
public record WebhookSubscription(String id, UUID principalId, String url,
                                  Set<EventPattern> eventPatterns, String signingSecret,
                                  SubscriptionState state, int consecutiveDeadDeliveries,
                                  Instant createdAt, Instant updatedAt) {

    /** URL scheme enforced by the domain and the V1 Flyway CHECK. */
    public static final Pattern HTTPS_URL =
            Pattern.compile("^https://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?(/\\S*)?$");

    /** Minimum/maximum secret lengths (webhooks.yaml WebhookEndpointCreateRequest). */
    public static final int MIN_SECRET_LENGTH = 16;
    public static final int MAX_SECRET_LENGTH = 256;

    /** Consecutive dead deliveries before the subscription auto-pauses. */
    public static final int AUTO_PAUSE_THRESHOLD = 3;

    public WebhookSubscription {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(principalId, "principalId is required");
        Objects.requireNonNull(url, "url is required");
        Objects.requireNonNull(eventPatterns, "eventPatterns are required");
        Objects.requireNonNull(signingSecret, "signingSecret is required");
        Objects.requireNonNull(state, "state is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (!HTTPS_URL.matcher(url).matches()) {
            throw new HttpsUrlRequiredException(url);
        }
        if (eventPatterns.isEmpty()) {
            throw new InvalidEventTypesException("at least one event type is required");
        }
        if (signingSecret.length() < MIN_SECRET_LENGTH
                || signingSecret.length() > MAX_SECRET_LENGTH) {
            throw new IllegalArgumentException("secret must be " + MIN_SECRET_LENGTH + ".."
                    + MAX_SECRET_LENGTH + " characters");
        }
        if (consecutiveDeadDeliveries < 0) {
            throw new IllegalArgumentException("consecutiveDeadDeliveries must not be negative");
        }
    }

    /** A freshly registered endpoint. */
    public static WebhookSubscription active(String id, UUID principalId, String url,
                                             Set<EventPattern> patterns, String secret,
                                             Instant now) {
        return new WebhookSubscription(id, principalId, url, Set.copyOf(patterns), secret,
                SubscriptionState.ACTIVE, 0, now, now);
    }

    /** Whether this endpoint subscribes to the (unversioned) event type. */
    public boolean matchesEvent(String catalogEventType) {
        return eventPatterns.stream().anyMatch(pattern -> pattern.matches(catalogEventType));
    }

    /** Operator pause: no new deliveries until resume. */
    public WebhookSubscription paused(Instant now) {
        return with(SubscriptionState.PAUSED, consecutiveDeadDeliveries, now);
    }

    /** Operator resume: reactivates and resets the auto-pause counter. */
    public WebhookSubscription resumed(Instant now) {
        return with(SubscriptionState.ACTIVE, 0, now);
    }

    /** Soft delete: in-flight deliveries complete, no new ones. */
    public WebhookSubscription deleted(Instant now) {
        return with(SubscriptionState.DELETED, consecutiveDeadDeliveries, now);
    }

    /**
     * A delivery to this endpoint just died. After
     * {@value #AUTO_PAUSE_THRESHOLD} consecutive dead deliveries the
     * subscription auto-pauses in state {@code DEAD} (webhooks.yaml
     * endpoint state) — surfaced in the Console, resumable by the operator.
     */
    public WebhookDeliveryOutcome recordDeadDelivery(Instant now) {
        int dead = consecutiveDeadDeliveries + 1;
        if (dead >= AUTO_PAUSE_THRESHOLD) {
            return new WebhookDeliveryOutcome(with(SubscriptionState.DEAD, dead, now), true);
        }
        return new WebhookDeliveryOutcome(with(state, dead, now), false);
    }

    /** A delivery succeeded: the consecutive-dead counter resets. */
    public WebhookSubscription recordDelivered(Instant now) {
        return with(state, 0, now);
    }

    private WebhookSubscription with(SubscriptionState newState, int dead, Instant now) {
        return new WebhookSubscription(id, principalId, url, eventPatterns, signingSecret,
                newState, dead, createdAt, now);
    }

    /** Result of recording a dead delivery: the new state + auto-pause flag. */
    public record WebhookDeliveryOutcome(WebhookSubscription subscription, boolean autoPaused) {
    }
}
