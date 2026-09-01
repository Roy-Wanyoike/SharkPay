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

class CounterpartyRuleTest {

    private final CounterpartyRule rule = new CounterpartyRule();

    private static EvaluationRequest request(String counterparty) {
        return EvaluationRequest.of(java.util.UUID.randomUUID().toString(), "subject-1",
                        PrincipalType.INDIVIDUAL, KycTier.LIMITED, Money.of(100_00L, "KES"), Channel.PAYMENT)
                .withCounterparty(counterparty);
    }

    @Test
    void reportsTheStableRuleId() {
        assertThat(rule.id()).isEqualTo("counterparty_denylist");
    }

    @Test
    void missingCounterpartyPasses() {
        RuleResult result = rule.evaluate(request(null), RuleSetConfig.defaults(),
                Mockito.mock(VelocityCounterStore.class));

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(result.reason()).contains("no counterparty");
    }

    @Test
    void denyListedCounterpartyIsDenied() {
        RuleSetConfig config = RuleSetConfigTestSupport.withDenylists(RuleSetConfig.defaults(),
                Set.of(), Set.of("shark_blocked", "shark_sanctioned"));

        RuleResult result = rule.evaluate(request("shark_blocked"), config,
                Mockito.mock(VelocityCounterStore.class));

        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        assertThat(result.reason()).contains("shark_blocked").contains("deny-listed");
    }

    @Test
    void allowedCounterpartyPasses() {
        RuleSetConfig config = RuleSetConfigTestSupport.withDenylists(RuleSetConfig.defaults(),
                Set.of(), Set.of("shark_blocked"));

        RuleResult result = rule.evaluate(request("shark_ok"), config,
                Mockito.mock(VelocityCounterStore.class));

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(result.reason()).contains("shark_ok").contains("allowed");
    }
}
