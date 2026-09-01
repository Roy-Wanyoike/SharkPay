package com.sharkpay.wallet.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudEventTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final String DATA = "data";

    @Test
    void buildsAValidEnvelope() {
        CloudEvent event = new CloudEvent("id-1", "wallet.hold.placed.v1", "1.0",
                "sharkpay/wallet", "hld_0123456789abcdef0123456789abcdef", T0, DATA);

        assertThat(event.id()).isEqualTo("id-1");
        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.source()).isEqualTo("sharkpay/wallet");
        assertThat(event.occurredAt()).isEqualTo(T0);
    }

    @Test
    void blankIdsTypesAndSubjectsAreRejected() {
        assertThatThrownBy(() -> new CloudEvent(" ", "t", "1.0", "s", "subj", T0, DATA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event id");
        assertThatThrownBy(() -> new CloudEvent("id-1", "", "1.0", "s", "subj", T0, DATA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event type");
        assertThatThrownBy(() -> new CloudEvent("id-1", "t", "1.0", "s", null, T0, DATA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void missingTimestampsAndPayloadsAreRejected() {
        assertThatThrownBy(() -> new CloudEvent("id-1", "t", "1.0", "s", "subj", null, DATA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> new CloudEvent("id-1", "t", "1.0", "s", "subj", T0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event data");
    }
}
