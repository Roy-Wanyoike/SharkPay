package com.sharkpay.risk.domain;

/**
 * Outcome of a single rule. {@code PASS} contributes nothing to the final
 * decision, {@code DENY} short-circuits the engine, {@code REVIEW} accumulates.
 */
public enum Outcome implements WireValue {

    PASS("pass"),
    DENY("deny"),
    REVIEW("review");

    private final String wire;

    Outcome(String wire) {
        this.wire = wire;
    }

    /** Contract wire value ({@code pass | deny | review}). */
    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    /** Parses a wire value; empty when the value is unknown. */
    public static java.util.Optional<Outcome> fromWire(String wire) {
        for (Outcome outcome : values()) {
            if (outcome.wire.equals(wire)) {
                return java.util.Optional.of(outcome);
            }
        }
        return java.util.Optional.empty();
    }
}
