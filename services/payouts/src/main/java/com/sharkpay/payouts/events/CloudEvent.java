package com.sharkpay.payouts.events;

import java.time.Instant;

/**
 * CloudEvents 1.0 envelope as published by this service, matching the
 * contracts/events/payouts.payout.v1.json and
 * contracts/events/transfers.transfer.v1.json schemas exactly (required
 * fields, no extras): {@code id, type, specversion, source, subject,
 * occurred_at, data}.
 *
 * <p>Consumers dedupe on {@code id} (UUID v7); {@code type} equals the Kafka
 * topic name; {@code source} is the producing service —
 * {@value #PAYOUTS_SOURCE} for payout events, {@value #TRANSFERS_SOURCE}
 * for transfer events (the transfers domain is owned by this service);
 * {@code subject} is the affected payout or transfer id.</p>
 */
public record CloudEvent(String id, String type, String specversion, String source, String subject,
                         Instant occurredAt, Object data) {

    public static final String SPECVERSION = "1.0";
    public static final String PAYOUTS_SOURCE = "sharkpay/payouts";
    public static final String TRANSFERS_SOURCE = "sharkpay/transfers";

    public CloudEvent {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("event id is required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type is required");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("event source is required");
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
