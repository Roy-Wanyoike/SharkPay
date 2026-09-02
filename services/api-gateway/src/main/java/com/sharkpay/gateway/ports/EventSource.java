package com.sharkpay.gateway.ports;

/**
 * Event source port: the production subscription binding (NATS/Kafka) that
 * starts consuming the domain topics and pushes envelopes into the
 * {@link EventConsumer}. Not auto-started by Spring: the integrator wires
 * the real binding at integration time (ADR 003 §3); the placeholder in
 * {@code config/IntegrationPendingEventSource} fails fast if started.
 */
public interface EventSource {

    /** Starts delivering events to the consumer; blocks or fails loudly. */
    void start(EventConsumer consumer);
}
