package com.sharkpay.risk.domain;

/**
 * When the evaluation runs: pre-authorization (before provider initiation) or
 * post-completion (docs/PRD.md FR-801). Carried on {@code risk.decision.v1}.
 */
public enum Phase implements WireValue {

    PRE("pre"),
    POST("post");

    private final String wire;

    Phase(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    public static java.util.Optional<Phase> fromWire(String wire) {
        for (Phase phase : values()) {
            if (phase.wire.equals(wire)) {
                return java.util.Optional.of(phase);
            }
        }
        return java.util.Optional.empty();
    }
}
