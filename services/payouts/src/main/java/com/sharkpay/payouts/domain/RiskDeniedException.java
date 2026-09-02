package com.sharkpay.payouts.domain;

/**
 * The risk decision intake rejected a payout that is not awaiting a risk
 * decision (409 state_conflict) — a DENY on a payout already past
 * PENDING_RISK arrives too late to block it.
 */
public class RiskDeniedException extends PayoutsDomainException {

    public RiskDeniedException(String payoutId, PayoutState state) {
        super("risk decision for payout " + payoutId + " arrives in state " + state.wireName()
                + "; only PENDING_RISK payouts can be blocked");
    }
}
