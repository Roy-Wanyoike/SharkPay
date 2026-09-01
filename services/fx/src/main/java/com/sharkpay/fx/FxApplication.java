package com.sharkpay.fx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SharkPay FX &amp; Multi-Currency Service (WP-7).
 *
 * <p>Production wiring (see {@link com.sharkpay.fx.config.FxConfig}): JPA
 * adapters against the Flyway-managed schema, the logging event publisher
 * until the Kafka adapter lands, and fail-fast placeholders for the
 * cross-service ports (providers rate feed, Go ledger posting API) until
 * the integrator wires the real REST adapters (ADR 003 §3). Security:
 * Keycloak JWT resource server on every endpoint except the actuator
 * health probes ({@code FX_ISSUER_URI}). Never booted in the local test
 * suite: tests exercise the hexagon via port fakes
 * ({@code com.sharkpay.fx.fakes} in src/test) and standalone MockMvc, per
 * ADR 003.</p>
 */
@SpringBootApplication
@EnableScheduling
public class FxApplication {

    public static void main(String[] args) {
        SpringApplication.run(FxApplication.class, args);
    }
}
