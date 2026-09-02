package com.sharkpay.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SharkPay Reconciliation Service (WP-10) — the outer consistency loop of
 * the platform (BACKEND-DESIGN §4).
 *
 * <p>Production wiring (see {@link com.sharkpay.reconciliation.config.ReconConfig}):
 * JPA adapters against the Flyway-managed schema, the logging event
 * publisher until the NATS/Kafka CloudEvent adapter lands, and fail-fast
 * placeholders for the cross-service ports (provider statement fetch, ledger
 * statement read, ledger compensation posting) until the integrator wires
 * the real REST adapters (ADR 003 §3). The aging sweeper runs on a fixed
 * delay ({@code recon.aging-sweep-interval-ms}). Never booted in the local
 * test suite: tests exercise the hexagon via port fakes and standalone
 * MockMvc.</p>
 */
@SpringBootApplication
@EnableScheduling
public class ReconciliationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}
