package com.sharkpay.risk.fakes;

import com.sharkpay.risk.events.CloudEvent;
import com.sharkpay.risk.ports.EventPublisher;

import java.util.List;
import java.util.Optional;

/** Records every published event for assertions. */
public final class RecordingEventPublisher implements EventPublisher {

    private final List<CloudEvent> events = new java.util.ArrayList<>();

    @Override
    public void publish(CloudEvent event) {
        events.add(event);
    }

    public List<CloudEvent> events() {
        return List.copyOf(events);
    }

    public List<CloudEvent> ofType(String type) {
        return events.stream().filter(e -> e.type().equals(type)).toList();
    }

    public Optional<CloudEvent> last() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1));
    }

    public void reset() {
        events.clear();
    }
}
