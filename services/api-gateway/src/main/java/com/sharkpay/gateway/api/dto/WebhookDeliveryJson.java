package com.sharkpay.gateway.api.dto;

import com.sharkpay.gateway.domain.WebhookDelivery;

import java.time.Instant;

/**
 * Webhook delivery JSON (the gateway's delivery-log read model): state,
 * attempts, backoff schedule and the last response seen.
 */
public record WebhookDeliveryJson(String id, String event_id, String type, String state,
                                  int attempts, Instant next_attempt_at, Integer last_response_code,
                                  String last_error, Instant created_at, Instant delivered_at) {

    public static WebhookDeliveryJson of(WebhookDelivery delivery) {
        return new WebhookDeliveryJson(delivery.id(), delivery.eventId(), delivery.eventType(),
                delivery.state().wireName(), delivery.attemptCount(), delivery.nextAttemptAt(),
                delivery.lastResponseCode(), delivery.lastError(), delivery.createdAt(),
                delivery.deliveredAt());
    }
}
