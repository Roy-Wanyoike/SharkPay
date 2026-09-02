package com.sharkpay.gateway.fakes;

import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.ports.WebhookDeliveryRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link WebhookDeliveryRepository} fake: (subscription, event)
 * uniqueness mirrors the storage constraint, due selection is oldest-first,
 * listing is newest-first with an id cursor.
 */
public final class InMemoryWebhookDeliveryRepository implements WebhookDeliveryRepository {

    private static final Comparator<WebhookDelivery> NEWEST_FIRST =
            Comparator.comparing(WebhookDelivery::createdAt).reversed()
                    .thenComparing(WebhookDelivery::id, Comparator.reverseOrder());

    private final Map<String, WebhookDelivery> deliveries = new ConcurrentHashMap<>();

    @Override
    public WebhookDelivery save(WebhookDelivery delivery) {
        if (findBySubscriptionAndEvent(delivery.subscriptionId(), delivery.eventId())
                .map(existing -> !existing.id().equals(delivery.id()))
                .orElse(false)) {
            throw new IllegalStateException("delivery for (subscription, event) already exists: "
                    + delivery.subscriptionId() + " / " + delivery.eventId());
        }
        deliveries.put(delivery.id(), delivery);
        return delivery;
    }

    @Override
    public Optional<WebhookDelivery> findById(String id) {
        return Optional.ofNullable(deliveries.get(id));
    }

    @Override
    public Optional<WebhookDelivery> findBySubscriptionAndEvent(String subscriptionId,
                                                                String eventId) {
        return deliveries.values().stream()
                .filter(delivery -> delivery.subscriptionId().equals(subscriptionId)
                        && delivery.eventId().equals(eventId))
                .findFirst();
    }

    @Override
    public List<WebhookDelivery> findDue(java.time.Instant now, int limit) {
        return deliveries.values().stream()
                .filter(delivery -> delivery.dueAt(now))
                .sorted(Comparator.comparing(WebhookDelivery::nextAttemptAt))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<WebhookDelivery> listBySubscription(String subscriptionId, int limit,
                                                    String cursor) {
        List<WebhookDelivery> page = deliveries.values().stream()
                .filter(delivery -> delivery.subscriptionId().equals(subscriptionId))
                .sorted(NEWEST_FIRST)
                .toList();
        if (cursor == null) {
            return page.stream().limit(Math.max(0, limit)).toList();
        }
        int index = -1;
        for (int i = 0; i < page.size(); i++) {
            if (page.get(i).id().equals(cursor)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return List.of();
        }
        return page.stream().skip(index + 1).limit(Math.max(0, limit)).toList();
    }

    /** Test oracle: everything ever persisted. */
    public Map<String, WebhookDelivery> all() {
        return Map.copyOf(deliveries);
    }
}
