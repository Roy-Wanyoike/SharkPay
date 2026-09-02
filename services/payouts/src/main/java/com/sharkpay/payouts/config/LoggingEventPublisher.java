package com.sharkpay.payouts.config;

import com.sharkpay.payouts.events.CloudEvent;
import com.sharkpay.payouts.ports.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder production {@link EventPublisher}: structured logging only.
 * The Kafka CloudEvent adapter (envelopes already match
 * contracts/events/payouts.payout.v1.json and transfers.transfer.v1.json
 * exactly) replaces this at the integration phase, per ADR 003.
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
