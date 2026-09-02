package com.sharkpay.payouts.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One recorded state change of an aggregate, persisted to the
 * {@code *_state_transitions} audit tables (docs/DATA-MACHINES.md §1,
 * STATE-MACHINES §7.3 replayability). Triggers and actors follow the
 * DATA-MODEL convention: {@code api|provider_callback|risk|expiry|ops|retry}
 * and {@code principal|system|provider|operator|scheduler}.
 */
public record StateTransition(Object from, Object to, String trigger, String actor, String note,
                              Instant occurredAt) {

    public StateTransition {
        Objects.requireNonNull(from, "from state is required");
        Objects.requireNonNull(to, "to state is required");
        Objects.requireNonNull(trigger, "trigger is required");
        if (trigger.isBlank()) {
            throw new IllegalArgumentException("trigger must not be blank");
        }
        Objects.requireNonNull(actor, "actor is required");
        if (actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required");
    }

    /** Wire name of the source state. */
    public String fromWire() {
        return wireOf(from);
    }

    /** Wire name of the target state. */
    public String toWire() {
        return wireOf(to);
    }

    private static String wireOf(Object state) {
        if (state instanceof PayoutState payoutState) {
            return payoutState.wireName();
        }
        if (state instanceof TransferState transferState) {
            return transferState.wireName();
        }
        return String.valueOf(state);
    }
}
