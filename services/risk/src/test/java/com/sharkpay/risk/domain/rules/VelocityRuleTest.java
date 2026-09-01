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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class VelocityRuleTest {

    private final VelocityRule rule = new VelocityRule();
    private final VelocityCounterStore counters = Mockito.mock(VelocityCounterStore.class);

    private static EvaluationRequest request() {
        return EvaluationRequest.of(java.util.UUID.randomUUID().toString(), "subject-1",
                PrincipalType.INDIVIDUAL, KycTier.LIMITED, Money.of(100_00L, "KES"), Channel.PAYMENT);
    }

    @Test
    void reportsTheStableRuleId() {
        assertThat(rule.id()).isEqualTo("velocity_window");
    }

    @Test
    void belowTheMaxPasses() {
        when(counters.countInWindow("subject-1", Duration.ofHours(1))).thenReturn(9);

        RuleResult result = rule.evaluate(request(), RuleSetConfig.defaults(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(result.reason()).contains("velocity ok").contains("9/10");
    }

    @Test
    void atTheMaxIsDenied() {
        when(counters.countInWindow("subject-1", Duration.ofHours(1))).thenReturn(10);

        RuleResult result = rule.evaluate(request(), RuleSetConfig.defaults(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        assertThat(result.reason()).contains("velocity exceeded").contains("10");
    }

    @Test
    void aboveTheMaxIsDenied() {
        when(counters.countInWindow("subject-1", Duration.ofHours(1))).thenReturn(11);

        RuleResult result = rule.evaluate(request(), RuleSetConfig.defaults(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        assertThat(result.reason()).contains("velocity exceeded");
    }

    @Test
    void usesTheConfiguredWindowNotAHardcodedOne() {
        RuleSetConfig tight = RuleSetConfigTestSupport.configWithVelocity(2, Duration.ofMinutes(30));
        when(counters.countInWindow("subject-1", Duration.ofMinutes(30))).thenReturn(2);

        RuleResult result = rule.evaluate(request(), tight, counters);

        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        Mockito.verify(counters).countInWindow("subject-1", Duration.ofMinutes(30));
    }
}
