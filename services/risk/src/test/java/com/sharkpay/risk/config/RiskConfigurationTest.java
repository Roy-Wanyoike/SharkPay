package com.sharkpay.risk.config;

import com.sharkpay.risk.service.AutoCasePolicy;
import com.sharkpay.risk.service.RulesEngine;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Production bean factories must build usable objects without a Spring context. */
class RiskConfigurationTest {

    private final RiskConfiguration config = new RiskConfiguration();

    @Test
    void clockBeanReturnsUtcNow() {
        Clock clock = config.riskClock();
        Instant before = Instant.now().minusSeconds(5);
        Instant after = Instant.now().plusSeconds(5);

        assertThat(clock).isNotNull();
        assertThat(clock.instant()).isBetween(before, after);
    }

    @Test
    void rulesEngineBeanUsesTheDocumentedRuleOrder() {
        RulesEngine engine = config.rulesEngine();

        assertThat(engine.rules())
                .extracting(com.sharkpay.risk.domain.Rule::id)
                .containsExactly("velocity_window", "tier_limit", "geo_denylist", "counterparty_denylist");
    }

    @Test
    void autoCasePolicyBeanOpensCasesOnDenyAndReview() {
        assertThat(config.autoCasePolicy()).isEqualTo(AutoCasePolicy.DEFAULT);
        assertThat(config.autoCasePolicy().opensOn(com.sharkpay.risk.domain.Decision.DENY)).isTrue();
        assertThat(config.autoCasePolicy().opensOn(com.sharkpay.risk.domain.Decision.REVIEW)).isTrue();
        assertThat(config.autoCasePolicy().opensOn(com.sharkpay.risk.domain.Decision.ALLOW)).isFalse();
    }
}
