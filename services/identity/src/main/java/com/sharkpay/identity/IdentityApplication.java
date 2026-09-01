package com.sharkpay.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SharkPay identity service (WP-1). Never booted in the local test suite:
 * tests exercise the hexagon via port fakes and standalone MockMvc.
 */
@SpringBootApplication
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
