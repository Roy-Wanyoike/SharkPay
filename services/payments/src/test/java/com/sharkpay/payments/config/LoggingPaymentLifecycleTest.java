package com.sharkpay.payments.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The logging lifecycle placeholder is the production PaymentLifecyclePort
 * while {@code temporal.enabled=false} (the default until the integrator
 * wires the worker + server): it must accept every hand-off without throwing
 * — the synchronous creation prefix still completes, provider results arrive
 * through the internal lifecycle API.
 */
class LoggingPaymentLifecycleTest {

    @Test
    void startNeverThrowsAndAcceptsAnyPaymentId() {
        LoggingPaymentLifecycle lifecycle = new LoggingPaymentLifecycle();
        assertThatCode(() -> lifecycle.start("pay_000000000000000000001"))
                .doesNotThrowAnyException();
        assertThatCode(() -> lifecycle.start("pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A"))
                .doesNotThrowAnyException();
        // repeated hand-offs are fine (fire-and-forget logging)
        assertThatCode(() -> lifecycle.start("pay_000000000000000000001"))
                .doesNotThrowAnyException();
    }
}
