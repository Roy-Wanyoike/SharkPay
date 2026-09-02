package com.sharkpay.payouts.domain;

import com.sharkpay.money.Money;

/** Available balance below the requested amount (422, details carried). */
public class InsufficientFundsException extends PayoutsDomainException {

    private final Money available;
    private final Money requested;

    public InsufficientFundsException(Money available, Money requested) {
        super("wallet balance after holds is " + available.amountMinor() + " "
                + available.currency() + ", requested " + requested.amountMinor() + " "
                + requested.currency());
        this.available = available;
        this.requested = requested;
    }

    public Money available() {
        return available;
    }

    public Money requested() {
        return requested;
    }
}
