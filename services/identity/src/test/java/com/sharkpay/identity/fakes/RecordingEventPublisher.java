package com.sharkpay.identity.fakes;

import com.sharkpay.identity.ports.event.CloudEvent;
import com.sharkpay.identity.ports.event.EventPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Event publisher fake that records every published {@link CloudEvent} for
 * assertions on type and payload.
 */
public final class RecordingEventPublisher implements EventPublisher {

    private final List<CloudEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(CloudEvent event) {
        events.add(event);
    }

    public List<CloudEvent> events() {
        return List.copyOf(events);
    }

    public List<CloudEvent> byType(String type) {
        return events.stream().filter(event -> event.type().equals(type)).toList();
    }

    public CloudEvent last() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    public void reset() {
        events.clear();
    }
}
