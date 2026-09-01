package com.sharkpay.identity.domain;

/**
 * KYC capability tier. Legal upgrades are single-step and forward only:
 * UNVERIFIED -&gt; LIMITED -&gt; FULL. Downgrades are not part of the public
 * flow; a suspension resets the tier to UNVERIFIED (re-verification).
 */
public enum KycTier {
    UNVERIFIED,
    LIMITED,
    FULL;

    /**
     * @return true when upgrading from this tier to {@code target} is a legal
     *         single forward step (tier skipping is rejected).
     */
    public boolean canAdvanceTo(KycTier target) {
        return switch (this) {
            case UNVERIFIED -> target == LIMITED;
            case LIMITED -> target == FULL;
            case FULL -> false;
        };
    }

    /** Monotonic rank used by consumers to order tier values (0, 1, 2). */
    public int rank() {
        return ordinal();
    }
}
