package com.sharkpay.wallet.config;

import com.sharkpay.wallet.events.CloudEvent;
import com.sharkpay.wallet.ports.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder production {@link EventPublisher}: structured logging only.
 * The NATS/Kafka CloudEvent adapter (envelopes already match
 * contracts/events/wallet*.json exactly) replaces this at the integration
 * phase, per ADR 003.
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
