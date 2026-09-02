package com.sharkpay.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recon window is half-open {@code [from, to)} — a line at exactly
 * {@code from} is inside, a line at exactly {@code to} is outside (matches
 * the providers gateway's {@code provider.Window}).
 */
class ReconWindowTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void aLineAtExactlyFromIsInsideAndALineAtExactlyToIsOutside() {
        ReconWindow window = new ReconWindow(FROM, TO);
        assertThat(window.contains(FROM)).isTrue();           // inclusive start
        assertThat(window.contains(FROM.plusSeconds(1))).isTrue();
        assertThat(window.contains(TO.minusSeconds(1))).isTrue();
        assertThat(window.contains(TO)).isFalse();            // exclusive end
        assertThat(window.contains(TO.plusSeconds(1))).isFalse();
    }

    @Test
    void fromMustBeStrictlyBeforeTo() {
        assertThatThrownBy(() -> new ReconWindow(TO, FROM))
                .isInstanceOf(InvalidWindowException.class)
                .hasMessageContaining("must be strictly before");
        assertThatThrownBy(() -> new ReconWindow(FROM, FROM))
                .isInstanceOf(InvalidWindowException.class)
                .hasMessageContaining("must be strictly before");
    }

    @Test
    void bothEndsAreMandatory() {
        assertThatThrownBy(() -> new ReconWindow(null, TO))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("from is required");
        assertThatThrownBy(() -> new ReconWindow(FROM, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("to is required");
    }

    @Test
    void containsRejectsANullInstant() {
        ReconWindow window = new ReconWindow(FROM, TO);
        assertThatThrownBy(() -> window.contains(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt is required");
    }

    @Test
    void canonicalIsTheIdempotencyFingerprintPart() {
        assertThat(new ReconWindow(FROM, TO).canonical())
                .isEqualTo(FROM + "|" + TO)
                .isNotEqualTo(new ReconWindow(FROM, TO.plusSeconds(1)).canonical());
    }
}
