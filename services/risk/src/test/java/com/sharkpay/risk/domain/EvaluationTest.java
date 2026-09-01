package com.sharkpay.risk.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationTest {

    private static final String ID = "1b9f2f4e-8c26-4a9e-9a3f-3f3b2a1c0d00";
    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    private static EvaluationRequest request() {
        return EvaluationRequest.of(ID, "subject-1", PrincipalType.INDIVIDUAL, KycTier.LIMITED,
                Money.of(100_00L, "KES"), Channel.PAYMENT);
    }

    private static List<RuleResult> oneRule() {
        return List.of(new RuleResult("velocity_window", Outcome.PASS, "ok"));
    }

    @Test
    void holdsTheDecisionAndReasons() {
        Evaluation evaluation = new Evaluation(ID, request(), Decision.ALLOW, oneRule(), T0);

        assertThat(evaluation.evaluationId()).isEqualTo(ID);
        assertThat(evaluation.decision()).isEqualTo(Decision.ALLOW);
        assertThat(evaluation.allowed()).isTrue();
        assertThat(evaluation.decidedAt()).isEqualTo(T0);
        assertThat(evaluation.ruleResults()).hasSize(1);

        Evaluation denied = new Evaluation(ID, request(), Decision.DENY, oneRule(), T0);
        assertThat(denied.allowed()).isFalse();
    }

    @Test
    void ruleResultsAreCopiedIntoAnImmutableList() {
        List<RuleResult> mutable = new java.util.ArrayList<>(oneRule());
        Evaluation evaluation = new Evaluation(ID, request(), Decision.ALLOW, mutable, T0);
        mutable.add(new RuleResult("geo_denylist", Outcome.PASS, "ok"));

        assertThat(evaluation.ruleResults()).hasSize(1);
        assertThatThrownBy(() -> evaluation.ruleResults().add(new RuleResult("x", Outcome.PASS, "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesItsInvariants() {
        assertThatThrownBy(() -> new Evaluation(null, request(), Decision.ALLOW, oneRule(), T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("evaluationId");
        assertThatThrownBy(() -> new Evaluation(ID, null, Decision.ALLOW, oneRule(), T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("request");
        assertThatThrownBy(() -> new Evaluation(ID, request(), null, oneRule(), T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("decision");
        assertThatThrownBy(() -> new Evaluation(ID, request(), Decision.ALLOW, oneRule(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("decidedAt");
        assertThatThrownBy(() -> new Evaluation(ID, request(), Decision.ALLOW, List.of(), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> new Evaluation(ID, request(), Decision.ALLOW, null, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Evaluation("6f1d3e2a-1111-2222-3333-444455556666", request(),
                Decision.ALLOW, oneRule(), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void ruleResultValidatesItsFields() {
        assertThatThrownBy(() -> new RuleResult(" ", Outcome.PASS, "ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleId");
        assertThatThrownBy(() -> new RuleResult("r", null, "ok"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outcome");
        assertThatThrownBy(() -> new RuleResult("r", Outcome.PASS, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");

        RuleResult result = new RuleResult("r", Outcome.DENY, "why");
        assertThat(result.ruleId()).isEqualTo("r");
        assertThat(result.outcome()).isEqualTo(Outcome.DENY);
        assertThat(result.reason()).isEqualTo("why");
        assertThat(result).isEqualTo(new RuleResult("r", Outcome.DENY, "why"));
    }

    @Test
    void decisionResultValidatesAndCopies() {
        assertThatThrownBy(() -> new DecisionResult(null, oneRule()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("decision");
        assertThatThrownBy(() -> new DecisionResult(Decision.ALLOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> new DecisionResult(Decision.ALLOW, null))
                .isInstanceOf(IllegalArgumentException.class);

        DecisionResult result = new DecisionResult(Decision.REVIEW, oneRule());
        assertThat(result.decision()).isEqualTo(Decision.REVIEW);
        assertThat(result.ruleResults()).hasSize(1);
        assertThatThrownBy(() -> result.ruleResults().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
