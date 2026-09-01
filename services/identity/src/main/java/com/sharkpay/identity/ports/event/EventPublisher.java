package com.sharkpay.identity.ports.event;

/**
 * Outbound event port (CloudEvents 1.0 envelopes). The production adapter
 * (NATS/Kafka at integration phase) maps {@link #time()} to the wire
 * {@code occurred_at} field and {@code subject} = entity id, per
 * contracts/events/events.md.
 */
public interface EventPublisher {

    void publish(CloudEvent event);
}
