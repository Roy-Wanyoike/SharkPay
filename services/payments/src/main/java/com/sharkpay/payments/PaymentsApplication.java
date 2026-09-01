package com.sharkpay.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SharkPay Payments Service (WP-5).
 *
 * <p>Production wiring (see {@link com.sharkpay.payments.config.PaymentsConfig}
 * and {@link com.sharkpay.payments.config.TemporalWorkerConfig}): JPA adapters
 * against the Flyway-managed schema, the logging event publisher until the
 * NATS JetStream CloudEvent adapter lands, fail-fast placeholders for the
 * cross-service ports (risk evaluation, wallet holds, ledger postings,
 * provider gateway) until the integrator wires the real REST/gRPC adapters
 * (ADR 003 §3), and — when {@code temporal.enabled=true} — a Temporal worker
 * on task queue {@value com.sharkpay.payments.workflow.PaymentWorkflow#TASK_QUEUE}
 * executing the payment lifecycle saga. Never booted in the local test suite:
 * tests exercise the hexagon via port fakes, standalone MockMvc and
 * TestWorkflowEnvironment.</p>
 */
@SpringBootApplication
public class PaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }
}
