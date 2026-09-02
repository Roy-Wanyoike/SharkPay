package com.sharkpay.gateway.ports;

import com.sharkpay.gateway.domain.WebhookDelivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Webhook delivery storage port: the at-least-once delivery log with the
 * (subscription, event id) delivery-idempotency guarantee — a delivered or
 * pending delivery for an event blocks re-creation, and only
 * {@code dead} deliveries can be revived by the operator replay.
 */
public interface WebhookDeliveryRepository {

    WebhookDelivery save(WebhookDelivery delivery);

    Optional<WebhookDelivery> findById(String id);

    Optional<WebhookDelivery> findBySubscriptionAndEvent(String subscriptionId, String eventId);

    /** Pending deliveries whose nextAttemptAt is due, oldest first. */
    List<WebhookDelivery> findDue(Instant now, int limit);

    /** Deliveries of one endpoint, newest-first, cursor-paginated. */
    List<WebhookDelivery> listBySubscription(String subscriptionId, int limit, String cursor);
}
