package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.events.CloudEventEnvelope;
import com.sharkpay.gateway.events.EnvelopeCodec;
import com.sharkpay.gateway.events.EventTypeCatalog;
import com.sharkpay.gateway.ports.EventConsumer;
import com.sharkpay.gateway.ports.Randomness;
import com.sharkpay.gateway.ports.WebhookDeliveryRepository;
import com.sharkpay.gateway.ports.WebhookSubscriptionRepository;

import java.time.Clock;
import java.util.Objects;

/**
 * The webhook fan-out dispatcher (the heart of the gateway): consumes every
 * domain event, resolves the internal topic to the unversioned public event
 * name, and creates one pending delivery per active subscription whose
 * event patterns match — unless that subscription already holds a delivery
 * for the event id (delivery idempotency: at-least-once, never duplicated).
 *
 * <p>Topics without a webhook catalog entry (risk decisions, ledger
 * postings — internal-only feeds) are ignored: the catalog is closed.</p>
 */
public final class DispatchEventUseCase implements EventConsumer {

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final Randomness randomness;
    private final EnvelopeCodec codec;
    private final Clock clock;

    public DispatchEventUseCase(WebhookSubscriptionRepository subscriptions,
                                WebhookDeliveryRepository deliveries, Randomness randomness,
                                EnvelopeCodec codec, Clock clock) {
        this.subscriptions = Objects.requireNonNull(subscriptions,
                "webhookSubscriptionRepository is required");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveryRepository is required");
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.codec = Objects.requireNonNull(codec, "codec is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public int onEvent(CloudEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope is required");
        EventTypeCatalog catalog = EventTypeCatalog.fromTopic(envelope.type()).orElse(null);
        if (catalog == null) {
            return 0;
        }
        String publicName = catalog.publicName();
        String payload = codec.outboundPayload(envelope, publicName);
        int created = 0;
        for (com.sharkpay.gateway.domain.WebhookSubscription subscription
                : subscriptions.listActive()) {
            if (!subscription.matchesEvent(publicName)) {
                continue;
            }
            if (deliveries.findBySubscriptionAndEvent(subscription.id(), envelope.id())
                    .isPresent()) {
                continue;
            }
            deliveries.save(WebhookDelivery.pending(randomness.webhookDeliveryId(),
                    subscription.id(), envelope.id(), publicName, payload, clock.instant()));
            created++;
        }
        return created;
    }
}
