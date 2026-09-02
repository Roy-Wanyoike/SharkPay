package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.SubscriptionState;
import com.sharkpay.gateway.domain.SubscriptionStateException;
import com.sharkpay.gateway.domain.WebhookSubscription;
import com.sharkpay.gateway.ports.WebhookSubscriptionRepository;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Webhook endpoint lifecycle (contracts/openapi/v1/webhooks.yaml
 * listWebhookEndpoints / getWebhookEndpoint / deleteWebhookEndpoint plus
 * the gateway's pause/resume): read, pause, resume and soft-delete, all
 * scoped to the owning principal. Soft-deleted endpoints are hidden (404)
 * but their row stays so in-flight deliveries can still complete
 * (webhooks.yaml delete semantics).
 */
public final class WebhookSubscriptionLifecycleUseCase {

    private final WebhookSubscriptionRepository subscriptions;
    private final Clock clock;

    public WebhookSubscriptionLifecycleUseCase(WebhookSubscriptionRepository subscriptions,
                                               Clock clock) {
        this.subscriptions = Objects.requireNonNull(subscriptions,
                "webhookSubscriptionRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** The endpoint, 404 when missing, soft-deleted or foreign. */
    public WebhookSubscription get(String id, UUID principal) {
        return owned(id, principal);
    }

    /** The caller's endpoints (never soft-deleted ones), cursor-paginated. */
    public List<WebhookSubscription> list(UUID principal, int limit, String cursor) {
        return subscriptions.listByPrincipal(principal, limit, cursor);
    }

    /** Operator pause: stops new deliveries; in-flight ones finish. */
    public WebhookSubscription pause(String id, UUID principal) {
        WebhookSubscription subscription = owned(id, principal);
        if (subscription.state() == SubscriptionState.DEAD) {
            throw new SubscriptionStateException("endpoint " + id
                    + " is auto-paused (dead) — resume it before pausing manually");
        }
        if (subscription.state() == SubscriptionState.ACTIVE) {
            return subscriptions.save(subscription.paused(clock.instant()));
        }
        return subscription;
    }

    /** Operator resume: reactivates paused/dead endpoints, resets the dead counter. */
    public WebhookSubscription resume(String id, UUID principal) {
        WebhookSubscription subscription = owned(id, principal);
        if (subscription.state() != SubscriptionState.ACTIVE) {
            return subscriptions.save(subscription.resumed(clock.instant()));
        }
        return subscription;
    }

    /** Soft delete (webhooks.yaml deleteWebhookEndpoint). Idempotent 404 when gone. */
    public void delete(String id, UUID principal) {
        WebhookSubscription subscription = owned(id, principal);
        subscriptions.save(subscription.deleted(clock.instant()));
    }

    private WebhookSubscription owned(String id, UUID principal) {
        WebhookSubscription subscription = subscriptions.findById(id)
                .orElseThrow(() -> new NoSuchElementException("webhook endpoint " + id + " not found"));
        if (subscription.state() == SubscriptionState.DELETED
                || !subscription.principalId().equals(principal)) {
            throw new NoSuchElementException("webhook endpoint " + id + " not found");
        }
        return subscription;
    }
}
