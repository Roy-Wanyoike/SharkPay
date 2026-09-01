package com.sharkpay.risk.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudEventTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void factoryBuildsAContractCompliantEnvelope() {
        CloudEvent event = CloudEvent.of("risk.decision.v1", "txn-1", T0,
                Map.of("decision", "allow"));

        assertThat(UUID.fromString(event.id())).isNotNull(); // UUID-shaped event id
        assertThat(event.type()).isEqualTo("risk.decision.v1");
        assertThat(event.source()).isEqualTo("sharkpay/risk");
        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.subject()).isEqualTo("txn-1");
        assertThat(event.occurredAt()).isEqualTo(T0);
        assertThat(event.data()).containsEntry("decision", "allow");
    }

    @Test
    void requiresTextFields() {
        Map<String, Object> data = Map.of("x", "y");
        assertThatThrownBy(() -> new CloudEvent(null, "t", "s", "1.0", "sub", T0, data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new CloudEvent(" ", "t", "s", "1.0", "sub", T0, data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new CloudEvent("id", null, "s", "1.0", "sub", T0, data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(() -> new CloudEvent("id", "t", "", "1.0", "sub", T0, data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(() -> new CloudEvent("id", "t", "s", "1.0", null, T0, data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void pinsSpecversionToOneDotZero() {
        assertThatThrownBy(() -> new CloudEvent("id", "t", "s", "2.0", "sub", T0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("specversion");
        assertThatThrownBy(() -> new CloudEvent("id", "t", "s", null, "sub", T0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("specversion");
    }

    @Test
    void occurredAtMustNotBeNull() {
        assertThatThrownBy(() -> new CloudEvent("id", "t", "s", "1.0", "sub", null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    void dataIsDefensiveCopiedAndDefaultsToEmpty() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");

        CloudEvent event = new CloudEvent("id", "t", "s", "1.0", "sub", T0, mutable);
        mutable.put("k", "changed");
        assertThat(event.data()).containsEntry("k", "v");

        assertThatThrownBy(() -> event.data().put("k", "nope"))
                .isInstanceOf(UnsupportedOperationException.class);

        CloudEvent noData = new CloudEvent("id", "t", "s", "1.0", "sub", T0, null);
        assertThat(noData.data()).isEmpty();
    }

    @Test
    void toJsonMapUsesTheContractFieldNames() {
        CloudEvent event = new CloudEvent("id-1", "risk.case.opened.v1", "sharkpay/risk", "1.0",
                "case_abc", T0, Map.of("case_id", "case_abc"));

        Map<String, Object> json = event.toJsonMap();

        assertThat(json).hasSize(7);
        assertThat(json.get("id")).isEqualTo("id-1");
        assertThat(json.get("type")).isEqualTo("risk.case.opened.v1");
        assertThat(json.get("specversion")).isEqualTo("1.0");
        assertThat(json.get("source")).isEqualTo("sharkpay/risk");
        assertThat(json.get("subject")).isEqualTo("case_abc");
        assertThat(json.get("occurred_at")).isEqualTo("2026-09-01T10:00:00Z");
        assertThat(json.get("data")).isEqualTo(Map.of("case_id", "case_abc"));
    }

    @Test
    void generatedEventIdsAreUnique() {
        CloudEvent first = CloudEvent.of("t", "s", T0, Map.of());
        CloudEvent second = CloudEvent.of("t", "s", T0, Map.of());
        assertThat(first.id()).isNotEqualTo(second.id());
    }
}
