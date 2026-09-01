package com.sharkpay.risk.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compliance case lifecycle.
 *
 * <pre>
 * OPEN -&gt; UNDER_REVIEW -&gt; CLOSED | ESCALATED
 *                 ^             |
 *                 +-------------+
 * </pre>
 *
 * {@code CLOSED} is the only terminal state. {@code ESCALATED -&gt;
 * UNDER_REVIEW} is the documented de-escalation edge so an escalated case can
 * still reach resolution; every other transition is illegal (docs
 * STATE-MACHINES.md: "any transition not listed here is a bug").
 *
 * Wire mapping note: the internal REST API reports {@code closed}; the
 * {@code risk.case.resolved.v1} event contract uses {@code resolved} for the
 * same state (see events.RiskEvents).
 */
public enum CaseStatus implements WireValue {

    OPEN("open"),
    UNDER_REVIEW("under_review"),
    CLOSED("closed"),
    ESCALATED("escalated");

    private static final Map<CaseStatus, Set<CaseStatus>> LEGAL = Map.of(
            OPEN, EnumSet.of(UNDER_REVIEW),
            UNDER_REVIEW, EnumSet.of(CLOSED, ESCALATED),
            ESCALATED, EnumSet.of(UNDER_REVIEW),
            CLOSED, EnumSet.noneOf(CaseStatus.class));

    private final String wire;

    CaseStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    /** True when no further transition is legal (CLOSED). */
    public boolean terminal() {
        return LEGAL.get(this).isEmpty();
    }

    public boolean canTransitionTo(CaseStatus target) {
        return LEGAL.get(this).contains(target);
    }

    /** States reachable from this state. */
    public Set<CaseStatus> legalTargets() {
        return Set.copyOf(LEGAL.get(this));
    }

    /** Parses a wire value; empty when the value is unknown. */
    public static Optional<CaseStatus> fromWire(String wire) {
        for (CaseStatus status : values()) {
            if (status.wire.equals(wire)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
