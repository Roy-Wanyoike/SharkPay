package com.sharkpay.risk.domain;

/**
 * KYC tier of the subject principal (docs/STATE-MACHINES.md 5: upgrades only).
 * Agents additionally follow the stricter agent policy (docs/PRD.md D8).
 */
public enum KycTier implements WireValue {

    UNVERIFIED("unverified"),
    LIMITED("limited"),
    FULL("full");

    private final String wire;

    KycTier(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    public static java.util.Optional<KycTier> fromWire(String wire) {
        for (KycTier tier : values()) {
            if (tier.wire.equals(wire)) {
                return java.util.Optional.of(tier);
            }
        }
        return java.util.Optional.empty();
    }
}
