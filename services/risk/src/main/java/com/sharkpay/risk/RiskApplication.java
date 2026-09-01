package com.sharkpay.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SharkPay Risk &amp; Compliance service (WP-8). Boot entry point only —
 * all behavior is unit tested without booting the context (ADR 003:
 * plain JUnit + standalone MockMvc).
 */
@SpringBootApplication
public class RiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }
}
