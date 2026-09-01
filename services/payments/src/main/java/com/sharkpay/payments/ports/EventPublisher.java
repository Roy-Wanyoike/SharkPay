package com.sharkpay.payments.ports;

import com.sharkpay.payments.events.CloudEvent;

/**
 * Outbound CloudEvent publisher (payments.payment.*.v1 on NATS JetStream at
 * integration; structured logging placeholder until then, ADR 003 §3).
 */
public interface EventPublisher {

    void publish(CloudEvent event);
}
