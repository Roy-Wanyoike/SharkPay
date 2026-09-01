package com.sharkpay.fx.config;

import com.sharkpay.fx.events.CloudEvent;
import com.sharkpay.fx.ports.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder production {@link EventPublisher}: structured logging only.
 * The Kafka (NATS in compose) CloudEvent adapter — envelopes already match
 * contracts/events/fx.v1.json exactly — replaces this at the integration
 * phase, per ADR 003 §3.
 */
public final class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(CloudEvent event) {
        log.info("cloudEvent specversion={} type={} source={} subject={} id={} occurred_at={} data={}",
                event.specversion(), event.type(), event.source(), event.subject(), event.id(),
                event.occurredAt(), event.data());
    }
}
