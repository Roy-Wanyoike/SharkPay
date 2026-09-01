package com.sharkpay.fx.fakes;

import com.sharkpay.fx.events.CloudEvent;
import com.sharkpay.fx.ports.EventPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording event publisher (fake for tests and local dev wiring; the Kafka
 * CloudEvent adapter lands at integration). Events are retained in order so
 * tests can assert the exact envelopes published.
 */
public final class RecordingEventPublisher implements EventPublisher {

    private final List<CloudEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(CloudEvent event) {
        events.add(event);
    }

    /** All published events, in publication order. */
    public List<CloudEvent> events() {
        return List.copyOf(events);
    }

    /** Events of one type, in publication order. */
    public List<CloudEvent> eventsOfType(String type) {
        return events.stream().filter(event -> event.type().equals(type)).toList();
    }
}
