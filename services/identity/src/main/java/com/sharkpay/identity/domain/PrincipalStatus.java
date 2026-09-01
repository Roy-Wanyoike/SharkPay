package com.sharkpay.identity.domain;

/**
 * Principal lifecycle status. CLOSED is terminal: no transition leaves CLOSED.
 * Legal transitions: ACTIVE -&gt; SUSPENDED, ACTIVE -&gt; CLOSED,
 * SUSPENDED -&gt; ACTIVE (reactivation), SUSPENDED -&gt; CLOSED.
 */
public enum PrincipalStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSED;
    }

    /**
     * @return true when the transition {@code this -> target} is legal.
     *         Re-entering the same status is not a transition and is rejected.
     */
    public boolean canTransitionTo(PrincipalStatus target) {
        if (this == target) {
            return false;
        }
        return switch (this) {
            case ACTIVE -> target == SUSPENDED || target == CLOSED;
            case SUSPENDED -> target == ACTIVE || target == CLOSED;
            case CLOSED -> false;
        };
    }
}
