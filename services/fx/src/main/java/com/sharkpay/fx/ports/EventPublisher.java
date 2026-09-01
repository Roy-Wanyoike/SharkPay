package com.sharkpay.fx.ports;

import com.sharkpay.fx.events.CloudEvent;

/**
 * Outbound event publisher. The current implementation is the recording
 * implementation is the structured-logging placeholder in
 * {@code com.sharkpay.fx.config} until the Kafka (NATS in compose) CloudEvent
 * adapter lands at integration; local tests use the in-tree recording fake
 * in {@code com.sharkpay.fx.fakes} (src/test). Envelopes follow
 * contracts/events/fx.v1.json.
 */
public interface EventPublisher {

    void publish(CloudEvent event);
}
