package com.sharkpay.payments.config;

import com.sharkpay.payments.ports.PaymentLifecyclePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder production {@link PaymentLifecyclePort}: logs the hand-off.
 * Active while {@code temporal.enabled=false} (the default until the
 * integrator wires the worker + server) — the synchronous creation prefix
 * still runs; provider results arrive through the internal lifecycle API
 * (or the Temporal worker once enabled).
 */
public final class LoggingPaymentLifecycle implements PaymentLifecyclePort {

    private static final Logger log = LoggerFactory.getLogger(LoggingPaymentLifecycle.class);

    @Override
    public void start(String paymentId) {
        log.info("paymentLifecycle pendingTemporalWorker payment={} "
                + "(temporal.enabled=false: results arrive via the internal provider-result API)",
                paymentId);
    }
}
