package com.sharkpay.risk.service;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.Channel;
import com.sharkpay.risk.domain.Decision;
import com.sharkpay.risk.domain.DecisionResult;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.PrincipalType;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.domain.rules.CounterpartyRule;
import com.sharkpay.risk.domain.rules.GeoRule;
import com.sharkpay.risk.domain.rules.LimitRule;
import com.sharkpay.risk.domain.rules.VelocityRule;
import com.sharkpay.risk.fakes.InMemoryVelocityCounterStore;
import com.sharkpay.risk.fakes.MutableClock;
import com.sharkpay.risk.fakes.StubRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RulesEngineTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryVelocityCounterStore counters = new InMemoryVelocityCounterStore(clock);
    private final RuleSetConfig config = RuleSetConfig.defaults();

    private static EvaluationRequest request() {
        return EvaluationRequest.of(java.util.UUID.randomUUID().toString(), "subject-1",
                PrincipalType.INDIVIDUAL, KycTier.LIMITED, Money.of(100_00L, "KES"), Channel.PAYMENT);
    }

    @Test
    void requiresAtLeastOneRule() {
        assertThatThrownBy(() -> new RulesEngine(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one rule");
        assertThatThrownBy(() -> new RulesEngine((List<com.sharkpay.risk.domain.Rule>) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void varargsConstructorBuildsTheSameEngine() {
        RulesEngine engine = new RulesEngine(StubRule.pass("a"), StubRule.pass("b"));
        assertThat(engine.rules()).hasSize(2);
        assertThat(engine.rules().get(0).id()).isEqualTo("a");
    }

    @Test
    void defaultEngineUsesTheDocumentedOrder() {
        RulesEngine engine = RulesEngine.defaultEngine();
        assertThat(engine.rules())
                .extracting(com.sharkpay.risk.domain.Rule::id)
                .containsExactly("velocity_window", "tier_limit", "geo_denylist", "counterparty_denylist");
        assertThat(engine.rules().get(0)).isInstanceOf(VelocityRule.class);
        assertThat(engine.rules().get(1)).isInstanceOf(LimitRule.class);
        assertThat(engine.rules().get(2)).isInstanceOf(GeoRule.class);
        assertThat(engine.rules().get(3)).isInstanceOf(CounterpartyRule.class);
    }

    @Test
    void allRulesPassingYieldsAllow() {
        StubRule first = StubRule.pass("first");
        StubRule second = StubRule.pass("second");
        RulesEngine engine = new RulesEngine(first, second);

        DecisionResult result = engine.evaluate(request(), config, counters);

        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.ruleResults()).extracting(com.sharkpay.risk.domain.RuleResult::ruleId)
                .containsExactly("first", "second");
        assertThat(first.invocations()).isEqualTo(1);
        assertThat(second.invocations()).isEqualTo(1);
    }

    @Test
    void denyShortCircuitsRemainingRules() {
        StubRule passing = StubRule.pass("first");
        StubRule denying = StubRule.deny("second");
        StubRule never = StubRule.review("third");
        RulesEngine engine = new RulesEngine(passing, denying, never);

        DecisionResult result = engine.evaluate(request(), config, counters);

        assertThat(result.decision()).isEqualTo(Decision.DENY);
        assertThat(result.ruleResults()).hasSize(2);
        assertThat(never.invocations()).isZero();
    }

    @Test
    void reviewOutcomesAccumulateToAReviewDecision() {
        RulesEngine engine = new RulesEngine(StubRule.pass("first"), StubRule.review("second"),
                StubRule.review("third"));

        DecisionResult result = engine.evaluate(request(), config, counters);

        assertThat(result.decision()).isEqualTo(Decision.REVIEW);
        assertThat(result.ruleResults()).hasSize(3);
    }

    @Test
    void denyWinsOverAccumulatedReview() {
        RulesEngine engine = new RulesEngine(StubRule.review("first"), StubRule.deny("second"),
                StubRule.pass("third"));

        DecisionResult result = engine.evaluate(request(), config, counters);

        assertThat(result.decision()).isEqualTo(Decision.DENY);
        assertThat(result.ruleResults()).hasSize(2);
    }

    @Test
    void nullArgumentsAreRejected() {
        RulesEngine engine = new RulesEngine(StubRule.pass("a"));
        EvaluationRequest request = request();

        assertThatThrownBy(() -> engine.evaluate(null, config, counters))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("request");
        assertThatThrownBy(() -> engine.evaluate(request, null, counters))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("config");
        assertThatThrownBy(() -> engine.evaluate(request, config, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("counters");
    }

    @Test
    void defaultEngineMatrixOnTheFakes() {
        RulesEngine engine = RulesEngine.defaultEngine();

        // within velocity + limits, no geo/counterparty signals: ALLOW
        assertThat(engine.evaluate(request(), config, counters).decision()).isEqualTo(Decision.ALLOW);

        // unverified tier: zero cap: DENY with tier_limit as the denying rule
        EvaluationRequest unverified = EvaluationRequest.of(
                java.util.UUID.randomUUID().toString(), "subject-1", PrincipalType.INDIVIDUAL,
                KycTier.UNVERIFIED, Money.of(10_00L, "KES"), Channel.PAYMENT);
        DecisionResult denied = engine.evaluate(unverified, config, counters);
        assertThat(denied.decision()).isEqualTo(Decision.DENY);
        assertThat(denied.ruleResults())
                .filteredOn(r -> r.outcome() == com.sharkpay.risk.domain.Outcome.DENY)
                .extracting(com.sharkpay.risk.domain.RuleResult::ruleId)
                .containsExactly("tier_limit");
    }
}
