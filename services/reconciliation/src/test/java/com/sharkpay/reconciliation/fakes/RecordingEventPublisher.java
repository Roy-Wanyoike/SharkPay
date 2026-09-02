package com.sharkpay.reconciliation.fakes;

import com.sharkpay.reconciliation.events.CloudEvent;
import com.sharkpay.reconciliation.ports.EventPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording event publisher (in-tree test fake, src/test, ADR 003 §3). The
 * production wiring logs events until the NATS/Kafka CloudEvent adapter
 * lands at integration; tests record every envelope in publication order
 * so they can assert the exact events the use cases emit (and drive them
 * through the recon.v1.json contract check).
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

    /** Total published events (no-double-effect oracle). */
    public int count() {
        return events.size();
    }

    /** Drops all recorded events (between test phases). */
    public void reset() {
        events.clear();
    }
}
