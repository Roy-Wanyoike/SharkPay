package com.sharkpay.payouts.domain;

/**
 * A payout transition outside the docs/STATE-MACHINES.md §2 table was
 * attempted (409 state_conflict) — "any transition not listed there is a
 * bug".
 */
public class PayoutStateException extends PayoutsDomainException {

    private final String payoutId;
    private final PayoutState from;
    private final PayoutState attempted;

    public PayoutStateException(String payoutId, PayoutState from, PayoutState attempted) {
        super("payout " + payoutId + " is in state " + from.wireName()
                + " and cannot transition to " + attempted.wireName());
        this.payoutId = payoutId;
        this.from = from;
        this.attempted = attempted;
    }

    public String payoutId() {
        return payoutId;
    }

    public PayoutState from() {
        return from;
    }

    public PayoutState attempted() {
        return attempted;
    }
}
