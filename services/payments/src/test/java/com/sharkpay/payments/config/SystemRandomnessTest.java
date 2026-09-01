package com.sharkpay.payments.config;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production {@link Randomness} contract: UUID v7 event ids (RFC 9562 —
 * consumers dedupe on them, events.md), wire-patterned public payment ids
 * (payments.yaml {@code ^pay_[0-9A-Za-z]{20,}$}) and request ids. Time
 * ordering of successive v7 ids is monotonic (Kafka partition ordering relies
 * on subject, but v7 keeps logs sortable).
 */
class SystemRandomnessTest {

    private final SystemRandomness randomness = new SystemRandomness();

    @Test
    void uuidV7CarriesTheVersionAndVariantBits() {
        UUID id = randomness.uuidV7();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // 2 = RFC 9562 variant 10xx
        assertThat(id.toString())
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }

    @Test
    void successiveUuidV7sAreTimeMonotonicAndUnique() {
        UUID first = randomness.uuidV7();
        UUID second = randomness.uuidV7();
        assertThat(first).isNotEqualTo(second);
        // top 48 bits are the unix-epoch-millis timestamp (RFC 9562 §5.2)
        assertThat(second.getMostSignificantBits() >>> 16)
                .isGreaterThanOrEqualTo(first.getMostSignificantBits() >>> 16);
    }

    @Test
    void paymentIdsMatchTheWirePattern() {
        for (int i = 0; i < 16; i++) {
            assertThat(randomness.paymentId())
                    .matches("^pay_[0-9A-Za-z]{20,}$");
        }
    }

    @Test
    void requestIdsMatchTheEnvelopePattern() {
        for (int i = 0; i < 16; i++) {
            assertThat(randomness.requestId()).matches("^req_[0-9A-Za-z]+$");
        }
    }
}
