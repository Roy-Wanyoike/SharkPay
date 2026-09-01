package com.sharkpay.payments.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CloudEvents 1.0 envelope the payments service publishes: required
 * fields enforced at construction, constants pinned to the merged schema.
 */
class CloudEventTest {

    private static final Map<String, String> DATA = Map.of("payment_id", "pay_1");

    @Test
    void buildsAValidEnvelope() {
        CloudEvent event = new CloudEvent("0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                PaymentEvents.SUCCEEDED, CloudEvent.SPECVERSION, CloudEvent.SOURCE,
                "pay_0123456789abcdef0123456789abcdef", Instant.parse("2026-09-01T10:00:05Z"),
                DATA);

        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.source()).isEqualTo("sharkpay/payments");
        assertThat(event.type()).isEqualTo("payments.payment.succeeded.v1");
        assertThat(event.subject()).isEqualTo("pay_0123456789abcdef0123456789abcdef");
        assertThat(event.data()).isEqualTo(DATA);
    }

    @Test
    void rejectsBlankOrMissingEnvelopeFields() {
        Instant now = Instant.now();
        String id = "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d";
        assertThatThrownBy(() -> new CloudEvent(" ", PaymentEvents.CREATED, "1.0",
                "sharkpay/payments", "pay_1", now, DATA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event id");
        assertThatThrownBy(() -> new CloudEvent(id, null, "1.0", "sharkpay/payments", "pay_1",
                now, DATA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event type");
        assertThatThrownBy(() -> new CloudEvent(id, PaymentEvents.CREATED, "1.0",
                "sharkpay/payments", " ", now, DATA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event subject");
        assertThatThrownBy(() -> new CloudEvent(id, PaymentEvents.CREATED, "1.0",
                "sharkpay/payments", "pay_1", null, DATA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> new CloudEvent(id, PaymentEvents.CREATED, "1.0",
                "sharkpay/payments", "pay_1", now, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event data");
    }

    @Test
    void catalogTypeConstantsMatchTheSchemaEnum() {
        assertThat(PaymentEvents.CREATED).isEqualTo("payments.payment.created.v1");
        assertThat(PaymentEvents.PENDING_PROVIDER).isEqualTo("payments.payment.pending_provider.v1");
        assertThat(PaymentEvents.SUCCEEDED).isEqualTo("payments.payment.succeeded.v1");
        assertThat(PaymentEvents.FAILED).isEqualTo("payments.payment.failed.v1");
        assertThat(PaymentEvents.EXPIRED).isEqualTo("payments.payment.expired.v1");
        assertThat(PaymentEvents.REVERSED).isEqualTo("payments.payment.reversed.v1");
    }
}
