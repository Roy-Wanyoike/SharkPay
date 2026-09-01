package com.sharkpay.risk.domain;

/**
 * Final decision of a risk evaluation. Wire values are the lowercase forms
 * used by the {@code risk.decision.v1} event contract and the internal REST
 * API.
 */
public enum Decision implements WireValue {

    ALLOW("allow"),
    DENY("deny"),
    REVIEW("review");

    private final String wire;

    Decision(String wire) {
        this.wire = wire;
    }

    /** Contract wire value ({@code allow | deny | review}). */
    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    /** Parses a wire value; empty when the value is unknown. */
    public static java.util.Optional<Decision> fromWire(String wire) {
        for (Decision decision : values()) {
            if (decision.wire.equals(wire)) {
                return java.util.Optional.of(decision);
            }
        }
        return java.util.Optional.empty();
    }
}
