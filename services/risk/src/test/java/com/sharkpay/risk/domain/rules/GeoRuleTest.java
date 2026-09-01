package com.sharkpay.risk.domain.rules;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.Channel;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.Outcome;
import com.sharkpay.risk.domain.PrincipalType;
import com.sharkpay.risk.domain.RuleResult;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.ports.VelocityCounterStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GeoRuleTest {

    private final GeoRule rule = new GeoRule();

    private static EvaluationRequest request(String geo) {
        return EvaluationRequest.of(java.util.UUID.randomUUID().toString(), "subject-1",
                        PrincipalType.INDIVIDUAL, KycTier.LIMITED, Money.of(100_00L, "KES"), Channel.PAYMENT)
                .withGeo(geo);
    }

    @Test
    void reportsTheStableRuleId() {
        assertThat(rule.id()).isEqualTo("geo_denylist");
    }

    @Test
    void missingGeoPasses() {
        RuleResult result = rule.evaluate(request(null), RuleSetConfig.defaults(),
                Mockito.mock(VelocityCounterStore.class));

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(result.reason()).contains("no geo country");
    }

    @Test
    void denyListedCountryIsDenied() {
        RuleSetConfig config = RuleSetConfigTestSupport.withDenylists(RuleSetConfig.defaults(),
                Set.of("KP", "IR"), Set.of());

        RuleResult result = rule.evaluate(request("KP"), config, Mockito.mock(VelocityCounterStore.class));

        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        assertThat(result.reason()).contains("KP").contains("deny-listed");
    }

    @Test
    void allowedCountryPasses() {
        RuleSetConfig config = RuleSetConfigTestSupport.withDenylists(RuleSetConfig.defaults(),
                Set.of("KP"), Set.of());

        RuleResult result = rule.evaluate(request("KE"), config, Mockito.mock(VelocityCounterStore.class));

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(result.reason()).contains("KE").contains("allowed");
    }
}
