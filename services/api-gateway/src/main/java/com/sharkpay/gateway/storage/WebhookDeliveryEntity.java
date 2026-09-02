package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.domain.DeliveryState;
import com.sharkpay.gateway.domain.WebhookDelivery;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * JPA entity for the {@code webhook_deliveries} table — the at-least-once
 * delivery log. The unique (subscription, event id) constraint is the
 * delivery-idempotency guarantee in storage: one delivery per event per
 * endpoint, ever.
 */
@Entity
@Table(name = "webhook_deliveries",
        uniqueConstraints = @UniqueConstraint(name = "uq_deliveries_subscription_event",
                columnNames = {"subscription_id", "event_id"}))
public class WebhookDeliveryEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "subscription_id", nullable = false, length = 40)
    private String subscriptionId;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "state", nullable = false, length = 10)
    private String state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_response_code")
    private Integer lastResponseCode;

    @Column(name = "last_error", length = 256)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected WebhookDeliveryEntity() {
    }

    private WebhookDeliveryEntity(String id, String subscriptionId, String eventId,
                                  String eventType, String payload, String state,
                                  int attemptCount, Instant nextAttemptAt,
                                  Integer lastResponseCode, String lastError, Instant createdAt,
                                  Instant deliveredAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.state = state;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastResponseCode = lastResponseCode;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.deliveredAt = deliveredAt;
    }

    public static WebhookDeliveryEntity fromDomain(WebhookDelivery delivery) {
        return new WebhookDeliveryEntity(delivery.id(), delivery.subscriptionId(),
                delivery.eventId(), delivery.eventType(), delivery.payload(),
                delivery.state().name(), delivery.attemptCount(), delivery.nextAttemptAt(),
                delivery.lastResponseCode(), delivery.lastError(), delivery.createdAt(),
                delivery.deliveredAt());
    }

    public WebhookDelivery toDomain() {
        return new WebhookDelivery(id, subscriptionId, eventId, eventType, payload,
                DeliveryState.valueOf(state), attemptCount, nextAttemptAt, lastResponseCode,
                lastError, createdAt, deliveredAt);
    }

    public String getId() {
        return id;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    String getPayload() {
        return payload;
    }

    public String getState() {
        return state;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Integer getLastResponseCode() {
        return lastResponseCode;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    @Override
    public String toString() {
        return "WebhookDeliveryEntity{id=" + id + ", state=" + state + ", attempts="
                + attemptCount + "}";
    }
}
