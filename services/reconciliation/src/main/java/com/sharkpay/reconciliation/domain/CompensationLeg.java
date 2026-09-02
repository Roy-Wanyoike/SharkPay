package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;

import java.util.Objects;

/**
 * One leg of a compensation entry: the ledger account it touches (RB-7
 * names the typical targets — {@code suspense:recon:KES} for ops-owned
 * unresolved amounts, {@code honeycoin:settlement:KES} for settlement
 * variance), the side, and the positive amount.
 */
public record CompensationLeg(String accountRef, PostingDirection direction, Money amount) {

    public CompensationLeg {
        Objects.requireNonNull(accountRef, "accountRef is required");
        if (accountRef.isBlank()) {
            throw new IllegalArgumentException("accountRef must not be blank");
        }
        if (accountRef.length() > 128) {
            throw new IllegalArgumentException("accountRef must be at most 128 characters");
        }
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(amount, "amount is required");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("leg amount must be positive: " + amount);
        }
    }

    /** The mirrored leg against the same account (display convenience). */
    public CompensationLeg inverted() {
        return new CompensationLeg(accountRef, direction.opposite(), amount);
    }
}
