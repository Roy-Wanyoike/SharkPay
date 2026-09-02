package com.sharkpay.gateway.ports;

import com.sharkpay.gateway.events.CloudEventEnvelope;

/**
 * Inbound domain-event consumer port: the seam the event feed (NATS/Kafka
 * binding in production, the fake feed and the {@code POST /internal/events}
 * dev intake in tests) calls with every CloudEvent envelope. Implemented by
 * the webhook dispatcher use case (fan-out to matching subscriptions).
 *
 * <p>Contract (contracts/events/events.md): at-least-once per event;
 * consumers dedupe on {@code id}. The gateway's dedupe is the
 * (subscription, event id) delivery-idempotency store.</p>
 */
public interface EventConsumer {

    /**
     * Handles one event envelope. Envelope {@code type} is the internal
     * versioned topic name (e.g. {@code payments.payment.succeeded.v1});
     * topics without a webhook catalog entry are ignored (catalog closure —
     * see {@code EventTypeCatalog}).
     *
     * @return the number of new pending deliveries created (0 when the event
     *         matches no active subscription or was already delivered)
     */
    int onEvent(CloudEventEnvelope envelope);
}
