package com.sharkpay.wallet.fakes;

import com.sharkpay.wallet.ports.EventPublisher;
import com.sharkpay.wallet.events.CloudEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording event publisher (in-tree test fake (src/test, per ADR 003); the Kafka
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

    /** The most recently published event. */
    public CloudEvent last() {
        return events.get(events.size() - 1);
    }

    /** Drops all recorded events (between test phases). */
    public void reset() {
        events.clear();
    }
}
