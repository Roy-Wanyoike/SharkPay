package com.sharkpay.reconciliation.ports;

import com.sharkpay.reconciliation.events.CloudEvent;

/**
 * Outbound event publisher. The production wiring uses the logging
 * publisher (structured logging) until the NATS/Kafka CloudEvent adapter
 * lands at integration; tests use the recording fake in the test tree.
 * Envelopes follow the contracts/events/recon.v1.json schema.
 */
public interface EventPublisher {

    void publish(CloudEvent event);
}
