package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.domain.SubscriptionState;
import com.sharkpay.gateway.domain.WebhookSubscription;
import com.sharkpay.gateway.ports.WebhookSubscriptionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for the webhook subscription repository port. Soft-deleted
 * endpoints stay findable by id (in-flight deliveries complete and the
 * worker needs their signing secret) but are hidden from listings.
 */
@Repository
public final class JpaWebhookSubscriptionRepository implements WebhookSubscriptionRepository {

    private final WebhookSubscriptionJpaRepository jpa;

    public JpaWebhookSubscriptionRepository(WebhookSubscriptionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public WebhookSubscription save(WebhookSubscription subscription) {
        return jpa.findById(subscription.id())
                .map(entity -> {
                    entity.applyDomain(subscription);
                    return jpa.save(entity).toDomain();
                })
                .orElseGet(() -> jpa.save(WebhookSubscriptionEntity.fromDomain(subscription))
                        .toDomain());
    }

    @Override
    public Optional<WebhookSubscription> findById(String id) {
        return jpa.findById(id).map(WebhookSubscriptionEntity::toDomain);
    }

    @Override
    public List<WebhookSubscription> listByPrincipal(UUID principalId, int limit, String cursor) {
        return jpa.findByPrincipalIdOrderByIdAsc(principalId).stream()
                .filter(entity -> entity.getState() != null
                        && !SubscriptionState.DELETED.name().equals(entity.getState()))
                .filter(entity -> cursor == null || entity.getId().compareTo(cursor) > 0)
                .limit(Math.max(0, limit))
                .map(WebhookSubscriptionEntity::toDomain)
                .toList();
    }

    @Override
    public List<WebhookSubscription> listActive() {
        return jpa.findByStateOrderByIdAsc(SubscriptionState.ACTIVE.name()).stream()
                .map(WebhookSubscriptionEntity::toDomain)
                .toList();
    }
}
