package com.sharkpay.payouts.ports;

import com.sharkpay.payouts.events.CloudEvent;

/**
 * Outbound event publisher (CloudEvents 1.0 envelopes, contracts/events/*).
 * The Kafka CloudEvent adapter lands at integration; the logging publisher
 * keeps the envelopes observable in the meantime (ADR 003 §3). Delivery is
 * at-least-once; consumers dedupe on the event id (UUID v7).
 */
public interface EventPublisher {

    void publish(CloudEvent event);
}
