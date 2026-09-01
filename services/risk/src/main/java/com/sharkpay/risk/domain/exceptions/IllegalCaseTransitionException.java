package com.sharkpay.risk.domain.exceptions;

import com.sharkpay.risk.domain.CaseStatus;

/**
 * Case state machine violation — the requested transition is not one of the
 * legal edges (docs/STATE-MACHINES.md: "any transition not listed here is a
 * bug").
 */
public class IllegalCaseTransitionException extends RiskException {

    private final String caseId;
    private final CaseStatus from;
    private final CaseStatus attempted;

    public IllegalCaseTransitionException(String caseId, CaseStatus from, CaseStatus attempted) {
        super("Case " + caseId + " cannot transition from " + from.wire() + " to " + attempted.wire()
                + " (legal transitions: OPEN->UNDER_REVIEW, UNDER_REVIEW->CLOSED|ESCALATED, ESCALATED->UNDER_REVIEW;"
                + " CLOSED is terminal)");
        this.caseId = caseId;
        this.from = from;
        this.attempted = attempted;
    }

    public String caseId() {
        return caseId;
    }

    public CaseStatus from() {
        return from;
    }

    public CaseStatus attempted() {
        return attempted;
    }
}
