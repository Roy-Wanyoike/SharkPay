package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.domain.EventPattern;
import com.sharkpay.gateway.domain.SubscriptionState;
import com.sharkpay.gateway.domain.WebhookSubscription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA entity for the {@code webhook_subscriptions} table. Event patterns
 * are stored as the sorted, comma-joined pattern texts
 * ({@code payment.*,payout.created}). Soft-deleted endpoints stay in the
 * table (in-flight deliveries complete) but are filtered by the adapters.
 */
@Entity
@Table(name = "webhook_subscriptions")
public class WebhookSubscriptionEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    @Column(name = "event_patterns", nullable = false, length = 512)
    private String eventPatterns;

    @Column(name = "signing_secret", nullable = false, length = 256)
    private String signingSecret;

    @Column(name = "state", nullable = false, length = 8)
    private String state;

    @Column(name = "consecutive_dead_deliveries", nullable = false)
    private int consecutiveDeadDeliveries;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookSubscriptionEntity() {
    }

    private WebhookSubscriptionEntity(String id, UUID principalId, String url,
                                      String eventPatterns, String signingSecret, String state,
                                      int consecutiveDeadDeliveries, Instant createdAt,
                                      Instant updatedAt) {
        this.id = id;
        this.principalId = principalId;
        this.url = url;
        this.eventPatterns = eventPatterns;
        this.signingSecret = signingSecret;
        this.state = state;
        this.consecutiveDeadDeliveries = consecutiveDeadDeliveries;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static WebhookSubscriptionEntity fromDomain(WebhookSubscription subscription) {
        return new WebhookSubscriptionEntity(subscription.id(), subscription.principalId(),
                subscription.url(), joinPatterns(subscription), subscription.signingSecret(),
                subscription.state().name(), subscription.consecutiveDeadDeliveries(),
                subscription.createdAt(), subscription.updatedAt());
    }

    public WebhookSubscription toDomain() {
        Set<EventPattern> patterns = new LinkedHashSet<>();
        for (String pattern : eventPatterns.split(",")) {
            patterns.add(EventPattern.of(pattern));
        }
        return new WebhookSubscription(id, principalId, url, Set.copyOf(patterns), signingSecret,
                SubscriptionState.valueOf(state), consecutiveDeadDeliveries, createdAt, updatedAt);
    }

    /** Refreshes the mutable lifecycle fields from the domain object. */
    public void applyDomain(WebhookSubscription subscription) {
        this.state = subscription.state().name();
        this.consecutiveDeadDeliveries = subscription.consecutiveDeadDeliveries();
        this.updatedAt = subscription.updatedAt();
    }

    private static String joinPatterns(WebhookSubscription subscription) {
        return subscription.eventPatterns().stream()
                .map(EventPattern::pattern)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    public String getId() {
        return id;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public String getUrl() {
        return url;
    }

    String getEventPatterns() {
        return eventPatterns;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public String getState() {
        return state;
    }

    public int getConsecutiveDeadDeliveries() {
        return consecutiveDeadDeliveries;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "WebhookSubscriptionEntity{id=" + id + ", state=" + state + ", url=" + url + "}";
    }
}
