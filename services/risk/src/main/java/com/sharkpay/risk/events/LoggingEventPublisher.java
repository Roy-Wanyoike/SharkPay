package com.sharkpay.risk.events;

import com.sharkpay.risk.ports.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Default {@link EventPublisher} adapter: structured logging of the wire-map
 * JSON. The Kafka producer (one topic per event type) replaces this when the
 * event bus is wired in Wave 3 — the contract shape is already exactly what
 * this adapter serializes.
 */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingEventPublisher.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void publish(CloudEvent event) {
        try {
            LOG.info("risk event {}", objectMapper.writeValueAsString(event.toJsonMap()));
        } catch (Exception e) {
            LOG.warn("risk event {} (unserializable data: {})", event.type(), e.getMessage());
        }
    }
}
