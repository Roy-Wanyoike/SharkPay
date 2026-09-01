package com.sharkpay.payments.ports;

import com.sharkpay.money.Money;

import java.util.List;
import java.util.UUID;

/**
 * Consumer-driven port (ADR 003 §3) to the risk service's evaluation API.
 * The risk engine decides ALLOW / DENY / REVIEW with machine-readable
 * reasons; payments fails closed on anything but ALLOW.
 */
public interface RiskPort {

    /**
     * Evaluates one payment transaction. PRE_AUTHORIZATION gates intent
     * creation (hold placement); POST_AUTHORIZATION gates the
     * SUCCEEDED transition (STATE-MACHINES.md §1 guard: "SUCCEEDED is
     * reachable only after risk post-evaluation passed").
     */
    RiskDecision evaluate(RiskEvaluation evaluation);

    /** The evaluation request: what risk needs to know about the movement. */
    record RiskEvaluation(UUID principalId, String paymentId, Money amount, String rail,
                          String destinationWalletId, Phase phase) {
    }

    enum Phase {
        PRE_AUTHORIZATION, POST_AUTHORIZATION
    }

    /** ALLOW / DENY / REVIEW plus machine-readable reasons. */
    record RiskDecision(Decision decision, List<String> reasons, Integer kycTierRank) {

        public RiskDecision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public boolean allowed() {
            return decision == Decision.ALLOW;
        }

        /**
         * The principal's KYC tier rank for the router's tier gate (0
         * unverified, 1 limited, 2 full); null ranks lowest — fail closed.
         */
        public int tierRank() {
            return kycTierRank == null ? 0 : Math.max(0, Math.min(2, kycTierRank));
        }
    }

    enum Decision {
        ALLOW, DENY, REVIEW
    }
}
