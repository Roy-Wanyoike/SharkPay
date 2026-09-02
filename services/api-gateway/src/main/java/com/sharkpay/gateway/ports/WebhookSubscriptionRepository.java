package com.sharkpay.gateway.ports;

import com.sharkpay.gateway.domain.WebhookSubscription;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhook endpoint (subscription) storage port. {@code DELETED} endpoints
 * are soft-deleted: hidden from listings and lookups, but their row stays
 * so in-flight deliveries can still complete.
 */
public interface WebhookSubscriptionRepository {

    WebhookSubscription save(WebhookSubscription subscription);

    Optional<WebhookSubscription> findById(String id);

    /** Active/paused/dead endpoints of the principal, id-ordered, paginated. */
    List<WebhookSubscription> listByPrincipal(UUID principalId, int limit, String cursor);

    /** Every endpoint that may receive events right now (state ACTIVE). */
    List<WebhookSubscription> listActive();
}
