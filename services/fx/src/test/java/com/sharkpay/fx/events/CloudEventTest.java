package com.sharkpay.fx.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CloudEvents 1.0 envelope contract: all seven required fields must be
 * present and non-blank, specversion pinned to "1.0", source to
 * "sharkpay/fx" (mirrors the wallet service's CloudEventTest).
 */
class CloudEventTest {

    private static final Instant AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final Map<String, Object> DATA = Map.of("quote_id", "fxq_x");

    private CloudEvent valid() {
        return new CloudEvent("0192a7cf-1e2f-9a3b-9c4d-8e6f7a8b9c0d",
                FxEvents.QUOTE_LOCKED, CloudEvent.SPECVERSION, CloudEvent.SOURCE, "fxq_x", AT, DATA);
    }

    @Test
    void carriesTheCanonicalEnvelopeConstants() {
        assertThat(CloudEvent.SPECVERSION).isEqualTo("1.0");
        assertThat(CloudEvent.SOURCE).isEqualTo("sharkpay/fx");
        CloudEvent event = valid();
        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.source()).isEqualTo("sharkpay/fx");
        assertThat(event.data()).isEqualTo(DATA);
    }

    @Test
    void rejectsBlankIdsTypesAndSubjects() {
        assertThatThrownBy(() -> new CloudEvent("", FxEvents.QUOTE_LOCKED, "1.0",
                CloudEvent.SOURCE, "fxq_x", AT, DATA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudEvent("id", " ", "1.0",
                CloudEvent.SOURCE, "fxq_x", AT, DATA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudEvent("id", FxEvents.QUOTE_LOCKED, "1.0",
                CloudEvent.SOURCE, null, AT, DATA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOccurredAtAndData() {
        assertThatThrownBy(() -> new CloudEvent("id", FxEvents.QUOTE_LOCKED, "1.0",
                CloudEvent.SOURCE, "fxq_x", null, DATA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudEvent("id", FxEvents.QUOTE_LOCKED, "1.0",
                CloudEvent.SOURCE, "fxq_x", AT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
