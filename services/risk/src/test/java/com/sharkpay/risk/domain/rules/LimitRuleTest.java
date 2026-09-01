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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class LimitRuleTest {

    private final LimitRule rule = new LimitRule();
    private final VelocityCounterStore counters = Mockito.mock(VelocityCounterStore.class);

    private static RuleSetConfig config() {
        return RuleSetConfig.defaults();
    }

    private static EvaluationRequest request(PrincipalType type, KycTier tier, Money amount) {
        return EvaluationRequest.of(java.util.UUID.randomUUID().toString(), "subject-1", type, tier,
                amount, Channel.PAYMENT);
    }

    private void windowAmounts(long daily, long weekly) {
        when(counters.amountInWindow(eq("subject-1"), eq("KES"), eq(Duration.ofHours(24))))
                .thenReturn(Money.of(daily, "KES"));
        when(counters.amountInWindow(eq("subject-1"), eq("KES"), eq(Duration.ofDays(7))))
                .thenReturn(Money.of(weekly, "KES"));
    }

    @Test
    void reportsTheStableRuleId() {
        assertThat(rule.id()).isEqualTo("tier_limit");
    }

    @Test
    void withinLimitsPassesWithADescriptiveReason() {
        windowAmounts(1_000_00L, 5_000_00L);

        RuleResult result = rule.evaluate(request(PrincipalType.INDIVIDUAL, KycTier.LIMITED,
                Money.of(100_00L, "KES")), config(), counters);

        assertThat(result.ruleId()).isEqualTo(LimitRule.ID);
        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(result.reason()).contains("individual/limited").contains("within limits");
    }

    @Test
    void unverifiedTierHasAZeroDailyCapAndIsDenied() {
        windowAmounts(0, 0);

        RuleResult result = rule.evaluate(request(PrincipalType.INDIVIDUAL, KycTier.UNVERIFIED,
                Money.of(10_00L, "KES")), config(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        assertThat(result.reason()).contains("zero").contains("requires KYC");
    }

    @Test
    void dailyCapExceededIsDenied() {
        windowAmounts(4_999_00L, 0);

        RuleResult result = rule.evaluate(request(PrincipalType.INDIVIDUAL, KycTier.LIMITED,
                Money.of(2_00L, "KES")), config(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        assertThat(result.reason()).contains("daily cap exceeded");
    }

    @Test
    void weeklyCapExceededIsDenied() {
        // daily still fine, weekly over: LIMITED weekly cap is 20_000_00
        windowAmounts(1_000_00L, 20_000_00L);

        RuleResult result = rule.evaluate(request(PrincipalType.INDIVIDUAL, KycTier.LIMITED,
                Money.of(1_00L, "KES")), config(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        assertThat(result.reason()).contains("weekly cap exceeded");
    }

    @Test
    void exactlyAtTheCapStillPasses() {
        windowAmounts(4_900_00L, 19_900_00L);

        RuleResult result = rule.evaluate(request(PrincipalType.INDIVIDUAL, KycTier.LIMITED,
                Money.of(100_00L, "KES")), config(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void agentSingleTransactionCapIsDenied() {
        windowAmounts(0, 0);

        RuleResult result = rule.evaluate(request(PrincipalType.AGENT, KycTier.FULL,
                Money.of(150_001_00L, "KES")), config(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        assertThat(result.reason()).contains("single-transaction cap").contains("agent");
    }

    @Test
    void agentWithinSingleCapPasses() {
        windowAmounts(0, 0);

        RuleResult result = rule.evaluate(request(PrincipalType.AGENT, KycTier.FULL,
                Money.of(150_000_00L, "KES")), config(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
    }

    @Test
    void crossCurrencyTransactionsDeferTheLimitCheck() {
        RuleResult result = rule.evaluate(request(PrincipalType.INDIVIDUAL, KycTier.FULL,
                Money.of(999_999_00L, "USD")), config(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(result.reason()).contains("cross-currency").contains("USD").contains("KES");
        Mockito.verifyNoInteractions(counters);
    }

    @Test
    void agentSingleCapOnlyAppliesInSameCurrency() {
        RuleResult result = rule.evaluate(request(PrincipalType.AGENT, KycTier.FULL,
                Money.of(999_999_00L, "USD")), config(), counters);

        assertThat(result.outcome()).isEqualTo(Outcome.PASS);
        assertThat(result.reason()).contains("cross-currency");
    }
}
