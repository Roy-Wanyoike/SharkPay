package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.domain.CaseResolution;
import com.sharkpay.risk.domain.CaseStatus;
import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.domain.exceptions.CaseNotFoundException;
import com.sharkpay.risk.domain.exceptions.EvaluationNotFoundException;
import com.sharkpay.risk.domain.exceptions.IllegalCaseTransitionException;
import com.sharkpay.risk.events.RiskEventTypes;
import com.sharkpay.risk.fakes.RiskHarness;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseUseCasesTest {

    private final RiskHarness harness = new RiskHarness();

    @Test
    void openCaseSavesPublishesAndReturnsTheAggregate() {
        Case opened = harness.openCase.open("subject-7", "manual ops review");

        assertThat(opened.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(opened.subjectPrincipalId()).isEqualTo("subject-7");
        assertThat(opened.reason()).isEqualTo("manual ops review");
        assertThat(opened.createdAt()).isEqualTo(RiskHarness.INITIAL_TIME);
        assertThat(harness.cases.all()).containsExactly(opened);

        assertThat(harness.events.events()).hasSize(1);
        com.sharkpay.risk.events.CloudEvent event = harness.events.last().orElseThrow();
        assertThat(event.type()).isEqualTo(RiskEventTypes.CASE_OPENED_V1);
        assertThat(event.data())
                .containsEntry("case_id", opened.publicId())
                .containsEntry("case_state", "open")
                .containsEntry("principal_id", "subject-7")
                .containsEntry("reason", "manual ops review");
    }

    @Test
    void openCaseTrimsInput() {
        Case opened = harness.openCase.open("  subject-8  ", "  reason  ");

        assertThat(opened.subjectPrincipalId()).isEqualTo("subject-8");
        assertThat(opened.reason()).isEqualTo("reason");
    }

    @Test
    void getCaseAcceptsPublicIdAndBareUuid() {
        Case opened = harness.openCase.open("subject-1", "reason");

        assertThat(harness.getCase.get(opened.publicId())).isSameAs(opened);
        assertThat(harness.getCase.get(opened.id().toString())).isSameAs(opened);
        assertThatThrownBy(() -> harness.getCase.get("case_zzz"))
                .isInstanceOf(com.sharkpay.risk.domain.exceptions.InvalidCaseIdException.class);
        assertThatThrownBy(() -> harness.getCase.get("case_00000000000000000000000000000000"))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void getEvaluationFindsStoredEvaluations() {
        Evaluation evaluation = harness.evaluateTransaction.evaluate(harness.allowedRequest());

        assertThat(harness.getEvaluation.get(evaluation.evaluationId())).isEqualTo(evaluation);
        assertThatThrownBy(() -> harness.getEvaluation.get("0d5c9a1e-7b3f-42a1-9c8d-1a2b3c4d5e6f"))
                .isInstanceOf(EvaluationNotFoundException.class)
                .hasMessageContaining("0d5c9a1e");
    }

    @Test
    void legalTransitionChainIsPersistedWithoutIntermediateEvents() {
        Case opened = harness.openCase.open("subject-1", "velocity spike");

        harness.clock.advance(Duration.ofMinutes(10));
        Case underReview = harness.transitionCase.transition(
                opened.publicId(), CaseStatus.UNDER_REVIEW, "op-1", null);

        assertThat(underReview.status()).isEqualTo(CaseStatus.UNDER_REVIEW);
        assertThat(underReview.updatedAt()).isEqualTo(RiskHarness.INITIAL_TIME.plus(Duration.ofMinutes(10)));
        assertThat(underReview.transitions()).hasSize(1);
        assertThat(underReview.transitions().get(0).actor()).isEqualTo("op-1");
        // intermediate transitions emit no event (contract registry: open/resolve only)
        assertThat(harness.events.events()).hasSize(1); // just the case.opened event

        assertThat(harness.cases.all().get(0).status()).isEqualTo(CaseStatus.UNDER_REVIEW);

        harness.clock.advance(Duration.ofMinutes(10));
        Case escalated = harness.transitionCase.transition(
                opened.id().toString(), CaseStatus.ESCALATED, "op-2", null);
        assertThat(escalated.status()).isEqualTo(CaseStatus.ESCALATED);

        Case back = harness.transitionCase.transition(
                opened.publicId(), CaseStatus.UNDER_REVIEW, "op-2", null);
        assertThat(back.status()).isEqualTo(CaseStatus.UNDER_REVIEW);
        assertThat(back.transitions()).hasSize(3);
    }

    @Test
    void closingRequiresAndDefaultsToClearedAndEmitsResolvedEvent() {
        Case opened = harness.openCase.open("subject-1", "reason");
        harness.transitionCase.transition(opened.publicId(), CaseStatus.UNDER_REVIEW, "op-1", null);
        harness.clock.advance(Duration.ofMinutes(30));

        // no resolution given: the use case defaults to CLEARED
        Case closed = harness.transitionCase.transition(opened.publicId(), CaseStatus.CLOSED, "op-3", null);

        assertThat(closed.status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(closed.transitions().get(1).resolution()).isEqualTo(CaseResolution.CLEARED);

        assertThat(harness.events.events()).hasSize(2);
        com.sharkpay.risk.events.CloudEvent resolved = harness.events.last().orElseThrow();
        assertThat(resolved.type()).isEqualTo(RiskEventTypes.CASE_RESOLVED_V1);
        assertThat(resolved.data())
                .containsEntry("case_id", opened.publicId())
                .containsEntry("case_state", "resolved")
                .containsEntry("resolution", "cleared")
                .containsEntry("resolved_by", "op-3");
    }

    @Test
    void closingWithAnExplicitResolutionCarriesItOnTheEvent() {
        Case opened = harness.openCase.open("subject-1", "reason");
        harness.transitionCase.transition(opened.publicId(), CaseStatus.UNDER_REVIEW, "op-1", null);

        harness.transitionCase.transition(opened.publicId(), CaseStatus.CLOSED, "op-3",
                CaseResolution.SAR_FILED);

        assertThat(harness.events.last().orElseThrow().data())
                .containsEntry("resolution", "sar_filed")
                .containsEntry("resolved_by", "op-3");
    }

    @Test
    void closedCasesAreTerminal() {
        Case opened = harness.openCase.open("subject-1", "reason");
        harness.transitionCase.transition(opened.publicId(), CaseStatus.UNDER_REVIEW, "op-1", null);
        harness.transitionCase.transition(opened.publicId(), CaseStatus.CLOSED, "op-2",
                CaseResolution.CLEARED);

        assertThatThrownBy(() -> harness.transitionCase.transition(
                opened.publicId(), CaseStatus.UNDER_REVIEW, "op-1", null))
                .isInstanceOf(IllegalCaseTransitionException.class)
                .hasMessageContaining("CLOSED is terminal");
        // no second resolved event for the rejected attempt
        assertThat(harness.events.ofType(RiskEventTypes.CASE_RESOLVED_V1)).hasSize(1);
    }

    @Test
    void illegalTransitionsAreRejectedBeforePersistingOrPublishing() {
        Case opened = harness.openCase.open("subject-1", "reason");
        int eventsBefore = harness.events.events().size();

        assertThatThrownBy(() -> harness.transitionCase.transition(
                opened.publicId(), CaseStatus.CLOSED, "op-1", CaseResolution.CLEARED))
                .isInstanceOf(IllegalCaseTransitionException.class);
        assertThatThrownBy(() -> harness.transitionCase.transition(
                opened.publicId(), CaseStatus.ESCALATED, "op-1", null))
                .isInstanceOf(IllegalCaseTransitionException.class);

        assertThat(opened.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(opened.transitions()).isEmpty();
        assertThat(harness.events.events()).hasSize(eventsBefore);
    }

    @Test
    void resolutionRulesAreEnforcedByTheAggregate() {
        Case opened = harness.openCase.open("subject-1", "reason");
        harness.transitionCase.transition(opened.publicId(), CaseStatus.UNDER_REVIEW, "op-1", null);

        // resolution is forbidden on intermediate transitions
        assertThatThrownBy(() -> harness.transitionCase.transition(
                opened.publicId(), CaseStatus.ESCALATED, "op-1", CaseResolution.CLEARED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only allowed when closing");
    }

    @Test
    void unknownCaseIs404Semantics() {
        assertThatThrownBy(() -> harness.transitionCase.transition(
                "case_00000000000000000000000000000000", CaseStatus.UNDER_REVIEW, "op-1", null))
                .isInstanceOf(CaseNotFoundException.class);
    }
}
