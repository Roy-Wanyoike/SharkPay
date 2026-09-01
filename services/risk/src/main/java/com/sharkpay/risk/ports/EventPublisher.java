package com.sharkpay.risk.ports;

import com.sharkpay.risk.events.CloudEvent;

/**
 * Outbound event port. Production wiring publishes to Kafka topics named by
 * the event {@code type} (contracts/events/events.md registry); tests record
 * events with an in-memory fake.
 */
public interface EventPublisher {

    void publish(CloudEvent event);
}
