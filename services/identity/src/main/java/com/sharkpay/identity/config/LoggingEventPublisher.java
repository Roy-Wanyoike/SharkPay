package com.sharkpay.identity.config;

import com.sharkpay.identity.ports.event.CloudEvent;
import com.sharkpay.identity.ports.event.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder production {@link EventPublisher}: structured logging only.
 * The NATS/Kafka CloudEvents adapter replaces this at the integration phase
 * (it maps {@code time} -&gt; {@code occurred_at} and derives
 * {@code subject} = SharkId, per contracts/events/identity.v1.json).
 */
public final class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(CloudEvent event) {
        log.info("cloudEvent specversion={} type={} source={} id={} time={} data={}",
                event.specversion(), event.type(), event.source(), event.id(), event.time(), event.data());
    }
}
