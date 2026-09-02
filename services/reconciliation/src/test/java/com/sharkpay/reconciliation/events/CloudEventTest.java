package com.sharkpay.reconciliation.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CloudEvents 1.0 envelope's own invariants (matching
 * contracts/events/recon.v1.json required fields, no extras).
 */
class CloudEventTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void theEnvelopeCarriesTheCloudEventsConstants() {
        CloudEvent event = event("id-1", "recon.run.completed.v1", "run_x");
        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.source()).isEqualTo("sharkpay/reconciliation");
        assertThat(event.type()).isEqualTo("recon.run.completed.v1");
        assertThat(event.subject()).isEqualTo("run_x");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.data()).isEqualTo("payload");
    }

    @Test
    void everyRequiredFieldIsEnforced() {
        assertThatThrownBy(() -> event(null, "recon.run.completed.v1", "run_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event id is required");
        assertThatThrownBy(() -> event(" ", "recon.run.completed.v1", "run_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event id is required");
        assertThatThrownBy(() -> event("id-1", null, "run_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event type is required");
        assertThatThrownBy(() -> event("id-1", " ", "run_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event type is required");
        assertThatThrownBy(() -> event("id-1", "recon.run.completed.v1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event subject is required");
        assertThatThrownBy(() -> event("id-1", "recon.run.completed.v1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event subject is required");
        assertThatThrownBy(() -> new CloudEvent("id-1", "recon.run.completed.v1", "1.0",
                "sharkpay/reconciliation", "run_x", null, "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event occurredAt is required");
        assertThatThrownBy(() -> new CloudEvent("id-1", "recon.run.completed.v1", "1.0",
                "sharkpay/reconciliation", "run_x", NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event data is required");
    }

    private static CloudEvent event(String id, String type, String subject) {
        return new CloudEvent(id, type, CloudEvent.SPECVERSION, CloudEvent.SOURCE, subject, NOW,
                "payload");
    }
}
