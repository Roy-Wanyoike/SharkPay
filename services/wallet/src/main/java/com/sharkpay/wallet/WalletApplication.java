package com.sharkpay.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SharkPay Wallet Service (WP-2).
 *
 * <p>Production wiring (see {@link com.sharkpay.wallet.config.WalletConfig}):
 * JPA adapters against the Flyway-managed schema, the logging event
 * publisher until the NATS/Kafka adapter lands, and fail-fast placeholders
 * for the cross-service ports (identity principal lookup, ledger account
 * provisioning) until the integrator wires the real REST adapters (ADR
 * 003 §3). The {@code POST /internal/ledger-events} intake feeds the
 * balance projection until the NATS/Kafka
 * {@code ledger.posting.committed.v1} binding lands. Never booted in the
 * local test suite: tests exercise the hexagon via port fakes and
 * standalone MockMvc.
 */
@SpringBootApplication
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
