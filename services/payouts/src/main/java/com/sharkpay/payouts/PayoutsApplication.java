package com.sharkpay.payouts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SharkPay Transfers &amp; Payouts Service (WP-6).
 *
 * <p>Production wiring (see {@link com.sharkpay.payouts.config.PayoutsConfig}):
 * JPA adapters against the Flyway-managed schema, the logging event publisher
 * until the Kafka CloudEvent adapter lands, and fail-fast placeholders for the
 * cross-service ports (Go ledger internal posting API, providers gateway,
 * wallet snapshot reads, identity principal lookup) until the integrator wires
 * the real REST adapters (ADR 003 §3). Security: Keycloak JWT resource server
 * on every endpoint except the actuator health probes
 * ({@code KEYCLOAK_ISSUER_URI}). Never booted in the local test suite: tests
 * exercise the hexagon via port fakes ({@code com.sharkpay.payouts.fakes} in
 * src/test) and standalone MockMvc, per ADR 003.</p>
 *
 * <p><b>Temporal wiring point.</b> This wave has no durable workflow engine:
 * the payout lifecycle is a plain state machine driven by
 * {@code @Scheduled} ticks (release due batches, TTL sweep, in-flight
 * provider polling) plus the {@code com.sharkpay.payouts.ports.SchedulerPort}
 * seam. When Temporal lands, lift each payout into a workflow started at
 * acceptance: the workflow signals the same use-cases
 * (release / provider-result / return / expire) that the REST + scheduler
 * adapters call today — the domain logic and the ledger posting keys stay
 * byte-identical, so in-flight payouts are safe to migrate by state.</p>
 */
@SpringBootApplication
@EnableScheduling
public class PayoutsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayoutsApplication.class, args);
    }
}
