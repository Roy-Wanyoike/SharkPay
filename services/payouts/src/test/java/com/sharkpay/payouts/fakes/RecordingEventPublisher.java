package com.sharkpay.payouts.fakes;

import com.sharkpay.payouts.events.CloudEvent;
import com.sharkpay.payouts.ports.EventPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording {@link EventPublisher} fake (the Kafka CloudEvent adapter lands
 * at integration). Events are retained in publication order so tests can
 * assert the exact envelopes — types, UUID v7 ids, payloads and
 * one-event-per-transition.
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

    /** Number of published events. */
    public int count() {
        return events.size();
    }

    /** Drops all recorded events (between test phases). */
    public void reset() {
        events.clear();
    }
}
