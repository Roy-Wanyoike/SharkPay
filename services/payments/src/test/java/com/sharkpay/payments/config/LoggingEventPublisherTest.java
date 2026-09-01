package com.sharkpay.payments.config;

import com.sharkpay.payments.events.CloudEvent;
import com.sharkpay.payments.events.PaymentEvents;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The logging publisher is the production EventPublisher placeholder until
 * the NATS/Kafka CloudEvent adapter lands (ADR 003 §3): publishing must never
 * throw and must accept every envelope the service emits.
 */
class LoggingEventPublisherTest {

    @Test
    void publishNeverThrowsOnEveryEmittedEnvelope() {
        LoggingEventPublisher publisher = new LoggingEventPublisher();
        PaymentEvents events = new PaymentEvents(new PaymentsTestEnv().randomness);
        PaymentEvents.PaymentData data = new PaymentEvents.PaymentData(
                "pay_000000000000000000001", "CREATED",
                new com.sharkpay.payments.api.dto.MoneyJson(150_000L, "KES", 2),
                new com.sharkpay.payments.api.dto.MoneyJson(750L, "KES", 2),
                PaymentsTestEnv.WALLET, "honeycoin", null, null, null);

        assertThatCode(() -> publisher.publish(new CloudEvent(
                "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d", PaymentEvents.CREATED,
                CloudEvent.SPECVERSION, CloudEvent.SOURCE, "pay_000000000000000000001",
                Instant.parse("2026-09-01T10:00:00Z"), data))).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(new CloudEvent(
                "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9e", PaymentEvents.SUCCEEDED,
                CloudEvent.SPECVERSION, CloudEvent.SOURCE, "pay_000000000000000000001",
                Instant.parse("2026-09-01T10:00:05Z"),
                new PaymentEvents.PaymentData("pay_000000000000000000001", "SUCCEEDED",
                        new com.sharkpay.payments.api.dto.MoneyJson(150_000L, "KES", 2),
                        new com.sharkpay.payments.api.dto.MoneyJson(750L, "KES", 2),
                        PaymentsTestEnv.WALLET, "honeycoin", null,
                        java.util.UUID.fromString("0192a7c5-1a2b-7c3d-9e4f-8a5b6c7d8e9f"),
                        "hc_1")))).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(new CloudEvent(
                "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9f", PaymentEvents.FAILED,
                CloudEvent.SPECVERSION, CloudEvent.SOURCE, "pay_000000000000000000001",
                Instant.parse("2026-09-01T10:02:11Z"),
                new PaymentEvents.PaymentData("pay_000000000000000000001", "FAILED",
                        new com.sharkpay.payments.api.dto.MoneyJson(150_000L, "KES", 2),
                        new com.sharkpay.payments.api.dto.MoneyJson(0L, "KES", 2),
                        PaymentsTestEnv.WALLET, "honeycoin", "provider_failed",
                        java.util.UUID.fromString("0192a7c7-3c4d-7e5f-9a6b-8c7d8e9f0a1b"),
                        null)))).doesNotThrowAnyException();
    }

    @Test
    void publishAcceptsAnArbitraryWellFormedEnvelope() {
        LoggingEventPublisher publisher = new LoggingEventPublisher();
        CloudEvent event = new CloudEvent("0192a7cf-1e2f-9a3b-9c4d-8e6f7a8b9c0d",
                "payments.payment.created.v1", CloudEvent.SPECVERSION, CloudEvent.SOURCE,
                "pay_000000000000000000002", Instant.parse("2026-09-01T10:00:06Z"),
                Map.of("payment_id", "pay_000000000000000000002"));
        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }
}
