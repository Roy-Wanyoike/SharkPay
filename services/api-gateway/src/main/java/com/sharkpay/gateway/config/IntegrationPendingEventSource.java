package com.sharkpay.gateway.config;

import com.sharkpay.gateway.ports.EventConsumer;
import com.sharkpay.gateway.ports.EventSource;

/**
 * Fail-fast placeholder {@link EventSource}: the real NATS/Kafka binding
 * (all topics per contracts/events/events.md, consumer of record
 * "api-gateway — all (fan-out)") lands at integration time (ADR 003 §3).
 * It is never auto-started by Spring — the integrator replaces this bean
 * with the binding that calls {@code start(dispatcher)}. Starting the
 * placeholder fails loudly so a mis-wired boot cannot pretend to consume.
 */
public final class IntegrationPendingEventSource implements EventSource {

    @Override
    public void start(EventConsumer consumer) {
        throw new IllegalStateException("EventSource adapter is not wired yet: the NATS/Kafka"
                + " subscription binding lands at integration time (ADR 003). The dev intake"
                + " POST /internal/events feeds the same dispatcher until then.");
    }
}
