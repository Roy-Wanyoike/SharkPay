package com.sharkpay.wallet.ports;

import com.sharkpay.wallet.events.CloudEvent;

/**
 * Outbound event publisher. The production wiring uses
 * {@link com.sharkpay.wallet.config.LoggingEventPublisher} (structured
 * logging) until the Kafka (NATS in compose) CloudEvent adapter lands at
 * integration; tests use the recording fake in the test tree. Envelopes
 * follow the contracts/events/wallet*.json schemas.
 */
public interface EventPublisher {

    void publish(CloudEvent event);
}
