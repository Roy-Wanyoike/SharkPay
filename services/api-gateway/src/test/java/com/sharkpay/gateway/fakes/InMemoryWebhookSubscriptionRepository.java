package com.sharkpay.gateway.fakes;

import com.sharkpay.gateway.domain.SubscriptionState;
import com.sharkpay.gateway.domain.WebhookSubscription;
import com.sharkpay.gateway.ports.WebhookSubscriptionRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link WebhookSubscriptionRepository} fake. Soft-deleted
 * endpoints remain findable by id but disappear from listings — the
 * contract the JPA adapter must satisfy.
 */
public final class InMemoryWebhookSubscriptionRepository
        implements WebhookSubscriptionRepository {

    private final Map<String, WebhookSubscription> subscriptions = new ConcurrentHashMap<>();

    @Override
    public WebhookSubscription save(WebhookSubscription subscription) {
        subscriptions.put(subscription.id(), subscription);
        return subscription;
    }

    @Override
    public Optional<WebhookSubscription> findById(String id) {
        return Optional.ofNullable(subscriptions.get(id));
    }

    @Override
    public List<WebhookSubscription> listByPrincipal(UUID principalId, int limit, String cursor) {
        return subscriptions.values().stream()
                .filter(subscription -> subscription.principalId().equals(principalId))
                .filter(subscription -> subscription.state() != SubscriptionState.DELETED)
                .sorted(Comparator.comparing(WebhookSubscription::id))
                .filter(subscription -> cursor == null
                        || subscription.id().compareTo(cursor) > 0)
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public List<WebhookSubscription> listActive() {
        return subscriptions.values().stream()
                .filter(subscription -> subscription.state() == SubscriptionState.ACTIVE)
                .sorted(Comparator.comparing(WebhookSubscription::id))
                .toList();
    }

    /** Test oracle: everything ever persisted. */
    public Map<String, WebhookSubscription> all() {
        return Map.copyOf(subscriptions);
    }
}
