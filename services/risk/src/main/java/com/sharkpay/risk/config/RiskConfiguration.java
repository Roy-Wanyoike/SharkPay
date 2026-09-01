package com.sharkpay.risk.config;

import com.sharkpay.risk.service.AutoCasePolicy;
import com.sharkpay.risk.service.RulesEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Production wiring. The Clock port is java.time.Clock (JDK abstraction):
 * the engine/velocity windows and case timestamps read it, tests inject a
 * mutable fake. Storage/event adapters are picked up as Spring Data
 * repositories / @Component beans; use-cases are @Service components.
 */
@Configuration
public class RiskConfiguration {

    @Bean
    public Clock riskClock() {
        return Clock.systemUTC();
    }

    /** Engine order: velocity, limits, geo, counterparty (first DENY wins). */
    @Bean
    public RulesEngine rulesEngine() {
        return RulesEngine.defaultEngine();
    }

    /** Auto-open compliance cases on DENY and REVIEW (default policy). */
    @Bean
    public AutoCasePolicy autoCasePolicy() {
        return AutoCasePolicy.DEFAULT;
    }
}
