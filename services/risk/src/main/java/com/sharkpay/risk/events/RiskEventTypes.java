package com.sharkpay.risk.events;

/**
 * Event types + source of the risk service, per contracts/events/risk.v1.json
 * and the topic registry in contracts/events/events.md (append-only).
 */
public final class RiskEventTypes {

    /** Risk decision (allow / deny / review) — feeds payment &amp; payout orchestration. */
    public static final String DECISION_V1 = "risk.decision.v1";

    /** Compliance case created. */
    public static final String CASE_OPENED_V1 = "risk.case.opened.v1";

    /** Compliance case closed (terminal, carries resolution). */
    public static final String CASE_RESOLVED_V1 = "risk.case.resolved.v1";

    /** CloudEvents source of this service. */
    public static final String SOURCE = "sharkpay/risk";

    private RiskEventTypes() {
    }
}
