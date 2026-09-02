package com.sharkpay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SharkPay API Gateway (WP-9) — API platform &amp; webhooks.
 *
 * <p>The public front door: scoped API-key authentication (SHA-256 hashed
 * secrets, plaintext shown exactly once, 24 h rotation grace window,
 * per-key rpm/monthly quotas), the HMAC-SHA256-signed webhook dispatcher
 * (at-least-once, exponential backoff 1 m → 1 h capped, 8 attempts, dead
 * deliveries + auto-pause, operator replay), the {@code /v1} passthrough
 * skeleton (route table → scope check → upstream port, with idempotent
 * response caching) and the clearly separated {@code /sandbox} simulated
 * provider. See {@link com.sharkpay.gateway.config.GatewayConfig} for the
 * production wiring and ADR 003 for the port/fake discipline.</p>
 *
 * <p>Never booted in the local test suite: tests exercise the hexagon via
 * port fakes, standalone MockMvc and a loopback HTTP server.</p>
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
