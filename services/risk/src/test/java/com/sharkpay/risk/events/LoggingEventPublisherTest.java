package com.sharkpay.risk.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingEventPublisherTest {

    private final LoggingEventPublisher publisher = new LoggingEventPublisher();

    @Test
    void serializesContractShapedEventsWithoutThrowing() {
        CloudEvent decision = CloudEvent.of(RiskEventTypes.DECISION_V1, "txn-1",
                Instant.parse("2026-09-01T10:00:00Z"),
                Map.of("decision", "allow", "rules_matched", java.util.List.of("velocity_window")));
        CloudEvent opened = CloudEvent.of(RiskEventTypes.CASE_OPENED_V1, "case_1",
                Instant.parse("2026-09-01T10:01:00Z"), Map.of("case_state", "open"));
        CloudEvent empty = CloudEvent.of(RiskEventTypes.CASE_RESOLVED_V1, "case_1",
                Instant.parse("2026-09-01T10:02:00Z"), null);

        assertThatCode(() -> publisher.publish(decision)).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(opened)).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(empty)).doesNotThrowAnyException();
    }

    @Test
    void unserializablePayloadsAreLoggedNotThrown() {
        // Object has no serializable properties: the fallback warn branch must
        // swallow the serialization failure instead of failing the caller.
        CloudEvent weird = CloudEvent.of("risk.decision.v1", "txn-2",
                Instant.parse("2026-09-01T10:00:00Z"), Map.of("oops", new Object()));

        assertThatCode(() -> publisher.publish(weird)).doesNotThrowAnyException();
    }
}
