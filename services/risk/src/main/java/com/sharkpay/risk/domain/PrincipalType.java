package com.sharkpay.risk.domain;

/** Kind of principal being evaluated (docs/PRD.md D1 identity graph). */
public enum PrincipalType implements WireValue {

    INDIVIDUAL("individual"),
    BUSINESS("business"),
    AGENT("agent");

    private final String wire;

    PrincipalType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    public static java.util.Optional<PrincipalType> fromWire(String wire) {
        for (PrincipalType type : values()) {
            if (type.wire.equals(wire)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }
}
