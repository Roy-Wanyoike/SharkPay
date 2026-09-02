package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.domain.DeliveryState;
import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.ports.WebhookDeliveryRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for the webhook delivery repository port. Due-delivery
 * selection reads only the pending set (the work queue) and filters by
 * nextAttemptAt; listing is newest-first with an id cursor.
 */
@Repository
public final class JpaWebhookDeliveryRepository implements WebhookDeliveryRepository {

    private static final Comparator<WebhookDelivery> NEWEST_FIRST =
            Comparator.comparing(WebhookDelivery::createdAt).reversed()
                    .thenComparing(WebhookDelivery::id, Comparator.reverseOrder());

    private final WebhookDeliveryJpaRepository jpa;

    public JpaWebhookDeliveryRepository(WebhookDeliveryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public WebhookDelivery save(WebhookDelivery delivery) {
        // deliveries are immutable value rows: state transitions replace the
        // row wholesale under the same id (merge semantics on an assigned id)
        return jpa.save(WebhookDeliveryEntity.fromDomain(delivery)).toDomain();
    }

    @Override
    public Optional<WebhookDelivery> findById(String id) {
        return jpa.findById(id).map(WebhookDeliveryEntity::toDomain);
    }

    @Override
    public Optional<WebhookDelivery> findBySubscriptionAndEvent(String subscriptionId,
                                                                String eventId) {
        return jpa.findBySubscriptionIdAndEventId(subscriptionId, eventId)
                .map(WebhookDeliveryEntity::toDomain);
    }

    @Override
    public List<WebhookDelivery> findDue(Instant now, int limit) {
        return jpa.findByStateOrderByNextAttemptAtAsc(DeliveryState.PENDING.name()).stream()
                .map(WebhookDeliveryEntity::toDomain)
                .filter(delivery -> delivery.dueAt(now))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<WebhookDelivery> listBySubscription(String subscriptionId, int limit,
                                                    String cursor) {
        List<WebhookDelivery> deliveries = jpa.findBySubscriptionId(subscriptionId).stream()
                .map(WebhookDeliveryEntity::toDomain)
                .sorted(NEWEST_FIRST)
                .toList();
        if (cursor == null) {
            return deliveries.stream().limit(Math.max(0, limit)).toList();
        }
        int index = -1;
        for (int i = 0; i < deliveries.size(); i++) {
            if (deliveries.get(i).id().equals(cursor)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return List.of();
        }
        return deliveries.stream().skip(index + 1).limit(Math.max(0, limit)).toList();
    }
}
