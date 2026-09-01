package com.sharkpay.risk.events;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.domain.CaseResolution;
import com.sharkpay.risk.domain.CaseStatus;
import com.sharkpay.risk.domain.Evaluation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps domain facts onto the {@code risk.v1.json} event payloads.
 *
 * <ul>
 *   <li>{@code risk.decision.v1} — every completed evaluation (decision +
 *       rules matched; {@code rules_matched} lists every rule that ran,
 *       which is always &gt;= 1 and satisfies the contract's minItems).</li>
 *   <li>{@code risk.case.opened.v1} — case creation ({@code case_state: open}).</li>
 *   <li>{@code risk.case.resolved.v1} — case closure ({@code case_state:
 *       resolved}; the internal REST status {@code closed} maps to the
 *       contract's {@code resolved}).</li>
 * </ul>
 *
 * Intermediate transitions (UNDER_REVIEW, ESCALATED) intentionally emit no
 * event: the topic registry defines types only for case creation and
 * resolution, and topics are append-only (adding one requires a new contract
 * row + ADR — integrator decision).
 */
public final class RiskEvents {

    private RiskEvents() {
    }

    /** risk.decision.v1 for a completed evaluation. */
    public static CloudEvent decisionCompleted(Evaluation evaluation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", evaluation.decision().wire());
        data.put("phase", evaluation.request().phase().wire());
        data.put("transaction_id", evaluation.request().transactionId());
        data.put("transaction_type", evaluation.request().transactionType().wire());
        data.put("principal_id", evaluation.request().subjectPrincipalId());
        data.put("rules_matched", evaluation.ruleResults().stream()
                .map(com.sharkpay.risk.domain.RuleResult::ruleId)
                .toList());
        return CloudEvent.of(RiskEventTypes.DECISION_V1, evaluation.request().transactionId(),
                evaluation.decidedAt(), data);
    }

    /** risk.case.opened.v1 for a newly created case. */
    public static CloudEvent caseOpened(Case c) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("case_id", c.publicId());
        data.put("case_state", CaseStatus.OPEN.wire());
        data.put("principal_id", c.subjectPrincipalId());
        data.put("reason", c.reason());
        return CloudEvent.of(RiskEventTypes.CASE_OPENED_V1, c.publicId(), c.createdAt(), data);
    }

    /** risk.case.resolved.v1 for a closed case. */
    public static CloudEvent caseResolved(Case c, CaseResolution resolution, String resolvedBy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("case_id", c.publicId());
        data.put("case_state", "resolved");
        data.put("principal_id", c.subjectPrincipalId());
        data.put("reason", c.reason());
        data.put("resolution", resolution.wire());
        data.put("resolved_by", resolvedBy);
        return CloudEvent.of(RiskEventTypes.CASE_RESOLVED_V1, c.publicId(), c.updatedAt(), data);
    }
}
