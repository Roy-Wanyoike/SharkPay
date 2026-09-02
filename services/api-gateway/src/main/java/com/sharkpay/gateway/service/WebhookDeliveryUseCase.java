package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.ports.WebhookDeliveryRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * List + replay webhook deliveries (the gateway's delivery-log read model
 * and the operator replay for dead deliveries).
 */
public final class WebhookDeliveryUseCase {

    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionLifecycleUseCase subscriptions;
    private final java.time.Clock clock;

    public WebhookDeliveryUseCase(WebhookDeliveryRepository deliveries,
                                  WebhookSubscriptionLifecycleUseCase subscriptions,
                                  java.time.Clock clock) {
        this.deliveries = Objects.requireNonNull(deliveries, "deliveryRepository is required");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** The endpoint's deliveries, newest first, cursor-paginated. */
    public List<WebhookDelivery> list(String subscriptionId, UUID principal, int limit,
                                      String cursor) {
        subscriptions.get(subscriptionId, principal);
        return deliveries.listBySubscription(subscriptionId, limit, cursor);
    }

    /**
     * Operator replay: re-queues a dead delivery (attempt counter restarts,
     * immediately due). Only dead deliveries — a delivered or pending one is
     * a state conflict.
     */
    public WebhookDelivery replay(String subscriptionId, String deliveryId, UUID principal) {
        subscriptions.get(subscriptionId, principal);
        WebhookDelivery delivery = deliveries.findById(deliveryId)
                .filter(candidate -> candidate.subscriptionId().equals(subscriptionId))
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "delivery " + deliveryId + " not found"));
        WebhookDelivery replayed = delivery.replayed(clock.instant());
        return deliveries.save(replayed);
    }
}
