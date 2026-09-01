package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Decision;
import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.domain.exceptions.EvaluationConflictException;
import com.sharkpay.risk.events.RiskEventTypes;
import com.sharkpay.risk.fakes.RiskHarness;
import com.sharkpay.risk.fakes.StubRule;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluateTransactionTest {

    private final RiskHarness harness = new RiskHarness();

    @Test
    void allowedEvaluationRecordsTheCounterPersistsAndPublishes() {
        EvaluationRequest request = harness.allowedRequest();

        Evaluation evaluation = harness.evaluateTransaction.evaluate(request);

        assertThat(evaluation.decision()).isEqualTo(Decision.ALLOW);
        assertThat(evaluation.allowed()).isTrue();
        assertThat(evaluation.decidedAt()).isEqualTo(RiskHarness.INITIAL_TIME);
        assertThat(evaluation.ruleResults()).hasSize(4);
        assertThat(harness.evaluations.size()).isEqualTo(1);
        assertThat(harness.evaluations.findById(request.evaluationId())).contains(evaluation);

        // documented ordering: counters record only ALLOW, with the clock instant
        assertThat(harness.counters.entries()).hasSize(1);
        assertThat(harness.counters.entries().get(0).subject()).isEqualTo("subject-1");
        assertThat(harness.counters.entries().get(0).at()).isEqualTo(RiskHarness.INITIAL_TIME);

        assertThat(harness.events.ofType(RiskEventTypes.DECISION_V1)).hasSize(1);
        assertThat(harness.events.events()).hasSize(1);
        assertThat(harness.cases.size()).isZero(); // ALLOW never auto-opens a case
    }

    @Test
    void deniedEvaluationDoesNotCountAndAutoOpensACase() {
        EvaluationRequest request = harness.deniedRequest();

        Evaluation evaluation = harness.evaluateTransaction.evaluate(request);

        assertThat(evaluation.decision()).isEqualTo(Decision.DENY);
        assertThat(harness.counters.entries()).isEmpty(); // denied never counts
        assertThat(harness.evaluations.size()).isEqualTo(1);

        assertThat(harness.events.ofType(RiskEventTypes.DECISION_V1)).hasSize(1);
        assertThat(harness.events.ofType(RiskEventTypes.CASE_OPENED_V1)).hasSize(1);
        assertThat(harness.cases.size()).isEqualTo(1);

        // the auto-open reason identifies the evaluation, decision and rule ids
        Optional<com.sharkpay.risk.domain.Case> opened = harness.cases.all().stream().findFirst();
        assertThat(opened).isPresent();
        String reason = opened.get().reason();
        assertThat(reason)
                .contains("auto-opened by risk evaluation " + request.evaluationId())
                .contains("decision=deny")
                .contains("tier_limit");
        assertThat(opened.get().subjectPrincipalId()).isEqualTo("subject-1");
        assertThat(opened.get().status()).isEqualTo(com.sharkpay.risk.domain.CaseStatus.OPEN);
    }

    @Test
    void reviewEvaluationDoesNotCountAndAutoOpensACase() {
        RiskHarness reviewHarness = new RiskHarness(new RulesEngine(StubRule.review("stub_rule")),
                AutoCasePolicy.DEFAULT);
        EvaluationRequest request = reviewHarness.request(KycTier.LIMITED,
                com.sharkpay.money.Money.of(100_00L, "KES"));

        Evaluation evaluation = reviewHarness.evaluateTransaction.evaluate(request);

        assertThat(evaluation.decision()).isEqualTo(Decision.REVIEW);
        assertThat(reviewHarness.counters.entries()).isEmpty();
        assertThat(reviewHarness.cases.size()).isEqualTo(1);
        assertThat(reviewHarness.events.ofType(RiskEventTypes.CASE_OPENED_V1)).hasSize(1);
    }

    @Test
    void autoCasePolicyDisabledOpensNothing() {
        RiskHarness noCases = new RiskHarness(new RulesEngine(StubRule.deny("stub_rule")),
                new AutoCasePolicy(false, false));
        EvaluationRequest request = noCases.request(KycTier.LIMITED,
                com.sharkpay.money.Money.of(100_00L, "KES"));

        Evaluation evaluation = noCases.evaluateTransaction.evaluate(request);

        assertThat(evaluation.decision()).isEqualTo(Decision.DENY);
        assertThat(noCases.cases.size()).isZero();
        assertThat(noCases.events.ofType(RiskEventTypes.CASE_OPENED_V1)).isEmpty();
    }

    @Test
    void idempotentReplayReturnsTheOriginalWithoutSideEffects() {
        EvaluationRequest request = harness.allowedRequest();
        Evaluation first = harness.evaluateTransaction.evaluate(request);

        harness.events.reset();
        harness.clock.advance(java.time.Duration.ofMinutes(5));

        Evaluation replay = harness.evaluateTransaction.evaluate(request);

        assertThat(replay).isEqualTo(first);
        assertThat(harness.evaluations.size()).isEqualTo(1);
        assertThat(harness.counters.entries()).hasSize(1); // no double-count
        assertThat(harness.events.events()).isEmpty();     // no re-publish
        assertThat(harness.cases.size()).isZero();
    }

    @Test
    void sameEvaluationIdWithADifferentPayloadIsAConflict() {
        EvaluationRequest request = harness.allowedRequest();
        Evaluation first = harness.evaluateTransaction.evaluate(request);

        EvaluationRequest different = harness.request(request.evaluationId(), KycTier.LIMITED,
                com.sharkpay.money.Money.of(999_00L, "KES"));

        assertThatThrownBy(() -> harness.evaluateTransaction.evaluate(different))
                .isInstanceOf(EvaluationConflictException.class)
                .hasMessageContaining(request.evaluationId());

        // state untouched by the rejected replay
        assertThat(harness.evaluations.size()).isEqualTo(1);
        assertThat(harness.evaluations.findById(request.evaluationId())).contains(first);
        assertThat(harness.counters.entries()).hasSize(1);
        assertThat(harness.events.ofType(RiskEventTypes.DECISION_V1)).hasSize(1);
    }

    @Test
    void velocityWindowEnforcedAfterTenAllowedTransactions() {
        // 10 allowed transactions fill both the velocity window (10/h) and stay
        // far below the LIMITED daily cap (10 x 100.00 = 1000.00 < 5000.00)
        harness.recordAllowedTransactions(10);
        assertThat(harness.counters.entries()).hasSize(10);

        EvaluationRequest eleventh = harness.request(KycTier.LIMITED,
                com.sharkpay.money.Money.of(100_00L, "KES"));
        Evaluation evaluation = harness.evaluateTransaction.evaluate(eleventh);

        assertThat(evaluation.decision()).isEqualTo(Decision.DENY);
        assertThat(evaluation.ruleResults())
                .filteredOn(r -> r.outcome() == com.sharkpay.risk.domain.Outcome.DENY)
                .extracting(com.sharkpay.risk.domain.RuleResult::ruleId)
                .containsExactly("velocity_window");
        // the denied 11th transaction does not extend the window
        assertThat(harness.counters.entries()).hasSize(10);
    }

    @Test
    void velocityWindowSlidesWithTime() {
        harness.ruleSets.setActive(withVelocityWindow(
                RuleSetConfig.defaults(), 1, java.time.Duration.ofHours(1)));

        harness.recordAllowedTransactions(1);
        harness.clock.advance(java.time.Duration.ofHours(2));

        EvaluationRequest later = harness.request(KycTier.LIMITED,
                com.sharkpay.money.Money.of(100_00L, "KES"));
        Evaluation evaluation = harness.evaluateTransaction.evaluate(later);

        assertThat(evaluation.decision()).isEqualTo(Decision.ALLOW);
        assertThat(harness.counters.entries()).hasSize(2);
    }

    @Test
    void deniedVelocityStillAutoOpensACaseOncePerEvaluation() {
        harness.ruleSets.setActive(withVelocityWindow(
                RuleSetConfig.defaults(), 1, java.time.Duration.ofHours(1)));

        // the first transaction fills the 1-per-hour window and is allowed
        harness.recordAllowedTransactions(1);

        EvaluationRequest request = harness.request(KycTier.LIMITED,
                com.sharkpay.money.Money.of(100_00L, "KES"));
        Evaluation evaluation = harness.evaluateTransaction.evaluate(request);

        assertThat(evaluation.decision()).isEqualTo(Decision.DENY);
        assertThat(harness.cases.size()).isEqualTo(1);
        assertThat(harness.events.ofType(RiskEventTypes.CASE_OPENED_V1)).hasSize(1);

        // replay is idempotent: no second case, no second decision event
        harness.events.reset();
        assertThat(harness.evaluateTransaction.evaluate(request).decision()).isEqualTo(Decision.DENY);
        assertThat(harness.cases.size()).isEqualTo(1);
        assertThat(harness.events.events()).isEmpty();
    }

    private static RuleSetConfig withVelocityWindow(RuleSetConfig base, int max, java.time.Duration window) {
        return new RuleSetConfig(base.ruleSetId(), base.version(), base.active(),
                new com.sharkpay.risk.domain.VelocityPolicy(max, window),
                new EnumMap<>(base.tierLimits()), base.agentLimits(),
                base.geoDenylist(), base.counterpartyDenylist());
    }
}
