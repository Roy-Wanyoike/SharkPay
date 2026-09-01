package com.sharkpay.wallet.domain;

import com.sharkpay.money.Money;

/**
 * A hold (or capture) was requested for more than the wallet's available
 * balance. The non-negative available-balance invariant is the rule being
 * defended: {@code available = total - active holds >= 0}.
 */
public class InsufficientFundsException extends WalletDomainException {

    private final Money available;
    private final Money requested;

    public InsufficientFundsException(Money available, Money requested) {
        super("insufficient funds: available " + available + " " + available.currency()
                + ", requested " + requested + " " + requested.currency());
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
