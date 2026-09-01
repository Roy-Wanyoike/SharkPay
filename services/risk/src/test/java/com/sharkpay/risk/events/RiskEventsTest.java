package com.sharkpay.risk.events;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.domain.CaseResolution;
import com.sharkpay.risk.domain.CaseStatus;
import com.sharkpay.risk.domain.Channel;
import com.sharkpay.risk.domain.Decision;
import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.Phase;
import com.sharkpay.risk.domain.PrincipalType;
import com.sharkpay.risk.domain.RuleResult;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEventsTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T11:30:00Z");

    @Test
    void decisionCompletedCarriesTheContractPayload() {
        EvaluationRequest request = new EvaluationRequest(
                "1b9f2f4e-8c26-4a9e-9a3f-3f3b2a1c0d00",
                "txn-042",
                "subject-1",
                PrincipalType.INDIVIDUAL,
                KycTier.LIMITED,
                Money.of(100_00L, "KES"),
                Channel.PAYMENT,
                null,
                null,
                Phase.POST,
                com.sharkpay.risk.domain.TransactionType.PAYMENT);
        Evaluation evaluation = new Evaluation(request.evaluationId(), request, Decision.DENY,
                List.of(new RuleResult("velocity_window", com.sharkpay.risk.domain.Outcome.DENY,
                        "velocity exceeded")), T0);

        CloudEvent event = RiskEvents.decisionCompleted(evaluation);

        assertThat(event.type()).isEqualTo("risk.decision.v1");
        assertThat(event.subject()).isEqualTo("txn-042");
        assertThat(event.occurredAt()).isEqualTo(T0);
        assertThat(event.source()).isEqualTo("sharkpay/risk");
        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.data())
                .containsEntry("decision", "deny")
                .containsEntry("phase", "post")
                .containsEntry("transaction_id", "txn-042")
                .containsEntry("transaction_type", "payment")
                .containsEntry("principal_id", "subject-1")
                .containsEntry("rules_matched", List.of("velocity_window"));
        assertThat(event.id()).isNotBlank();
    }

    @Test
    void caseOpenedCarriesTheContractPayload() {
        Case c = Case.open(UUID.fromString("1b9f2f4e-8c26-4a9e-9a3f-3f3b2a1c0d00"),
                "subject-2", "velocity spike", T0);

        CloudEvent event = RiskEvents.caseOpened(c);

        assertThat(event.type()).isEqualTo("risk.case.opened.v1");
        assertThat(event.subject()).isEqualTo(c.publicId());
        assertThat(event.occurredAt()).isEqualTo(T0);
        assertThat(event.data())
                .containsEntry("case_id", c.publicId())
                .containsEntry("case_state", "open")
                .containsEntry("principal_id", "subject-2")
                .containsEntry("reason", "velocity spike");
    }

    @Test
    void caseResolvedCarriesTheResolutionAndActor() {
        Case c = Case.open(UUID.randomUUID(), "subject-3", "geo mismatch", T0);
        c.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1);
        c.transitionTo(CaseStatus.CLOSED, "op-9", CaseResolution.SAR_FILED, T1.plusSeconds(60));

        CloudEvent event = RiskEvents.caseResolved(c, CaseResolution.SAR_FILED, "op-9");

        assertThat(event.type()).isEqualTo("risk.case.resolved.v1");
        assertThat(event.subject()).isEqualTo(c.publicId());
        assertThat(event.occurredAt()).isEqualTo(c.updatedAt());
        // the REST status 'closed' maps to the contract's 'resolved'
        assertThat(event.data())
                .containsEntry("case_id", c.publicId())
                .containsEntry("case_state", "resolved")
                .containsEntry("principal_id", "subject-3")
                .containsEntry("reason", "geo mismatch")
                .containsEntry("resolution", "sar_filed")
                .containsEntry("resolved_by", "op-9");
    }

    @Test
    void eventTypesAndSourceMatchTheContractRegistry() {
        assertThat(RiskEventTypes.DECISION_V1).isEqualTo("risk.decision.v1");
        assertThat(RiskEventTypes.CASE_OPENED_V1).isEqualTo("risk.case.opened.v1");
        assertThat(RiskEventTypes.CASE_RESOLVED_V1).isEqualTo("risk.case.resolved.v1");
        assertThat(RiskEventTypes.SOURCE).isEqualTo("sharkpay/risk");
    }
}
