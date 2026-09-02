package com.sharkpay.reconciliation.config;

import com.sharkpay.reconciliation.ports.EventPublisher;
import com.sharkpay.reconciliation.events.CloudEvent;

import java.util.logging.Logger;

/**
 * Event publisher that logs every CloudEvent as structured JSON-ish lines.
 * Production wiring until the NATS/Kafka CloudEvent adapter lands at
 * integration (ADR 003 §3); tests use the recording fake.
 */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger LOG = Logger.getLogger(LoggingEventPublisher.class.getName());

    @Override
    public void publish(CloudEvent event) {
        LOG.info(() -> "recon event: type=" + event.type() + " id=" + event.id()
                + " subject=" + event.subject() + " occurred_at=" + event.occurredAt());
    }
}
