package com.sharkpay.payments.events;

import java.time.Instant;

/**
 * CloudEvents 1.0 envelope as published by this service, matching the
 * contracts/events/payments.payment.v1.json schema exactly (required fields,
 * no extras): {@code id, type, specversion, source, subject, occurred_at,
 * data}.
 *
 * <p>Consumers dedupe on {@code id} (UUID v7); {@code type} equals the Kafka
 * topic name; {@code source} is the producing service
 * ({@value #SOURCE}); {@code subject} is the payment intent id.</p>
 */
public record CloudEvent(String id, String type, String specversion, String source, String subject,
                         Instant occurredAt, Object data) {

    public static final String SPECVERSION = "1.0";
    public static final String SOURCE = "sharkpay/payments";

    public CloudEvent {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("event id is required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("event subject is required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("event occurredAt is required");
        }
        if (data == null) {
            throw new IllegalArgumentException("event data is required");
        }
    }
}
