package com.sharkpay.risk.domain;

import java.util.Optional;

/**
 * Outcome recorded when a case is resolved ({@code risk.case.resolved.v1}
 * contract vocabulary; SAR = suspicious activity report, docs/SECURITY.md 5).
 */
public enum CaseResolution implements WireValue {

    CLEARED("cleared"),
    BLOCKED("blocked"),
    REVERSED("reversed"),
    SAR_FILED("sar_filed");

    private final String wire;

    CaseResolution(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    public static Optional<CaseResolution> fromWire(String wire) {
        for (CaseResolution resolution : values()) {
            if (resolution.wire.equals(wire)) {
                return Optional.of(resolution);
            }
        }
        return Optional.empty();
    }
}
