package com.sharkpay.wallet.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;

import java.util.Objects;

/**
 * The three balance partitions of a wallet (contracts/openapi/v1/wallets.yaml
 * WalletBalances, contracts/events/wallet.v1.json balances):
 *
 * <ul>
 *   <li>{@code total} — the ledger projection balance (sum of applied
 *       ledger.posting.committed.v1 legs; the ledger is the sole authority
 *       and guarantees {@code total >= 0});</li>
 *   <li>{@code held} — sum of the wallet's ACTIVE hold amounts;</li>
 *   <li>{@code pending} — in-flight incoming funds (always zero in V1: the
 *       ledger's wallet accounts never go negative and in-flight money sits
 *       in suspense accounts; the partition exists for contract
 *       compatibility);</li>
 *   <li>{@code available = total - held} — spendable now; every
 *       wallet-mediated operation keeps it {@code >= 0}.</li>
 * </ul>
 *
 * @param total   ledger-projected balance (never negative)
 * @param held    reserved by active holds (never negative, never above total
 *                under wallet-mediated operations)
 * @param pending in-flight incoming (zero in V1)
 */
public record Balances(Money total, Money held, Money pending) {

    public Balances {
        Objects.requireNonNull(total, "total is required");
        Objects.requireNonNull(held, "held is required");
        Objects.requireNonNull(pending, "pending is required");
        requireSameCurrency(total, held, "held");
        requireSameCurrency(total, pending, "pending");
        requireNonNegative(total, "total");
        requireNonNegative(held, "held");
        requireNonNegative(pending, "pending");
    }

    /** Balances of a wallet with no ledger lines and no holds. */
    public static Balances zero(String currency) {
        Money zero = Money.zero(currency);
        return new Balances(zero, zero, zero);
    }

    /** Spendable now: {@code total - held}. */
    public Money available() {
        return total.subtract(held);
    }

    private static void requireSameCurrency(Money reference, Money other, String what) {
        if (!reference.currency().equals(other.currency())) {
            throw new CurrencyMismatchException(reference.currency(), other.currency());
        }
    }

    private static void requireNonNegative(Money amount, String what) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException(what + " balance must never be negative: " + amount);
        }
    }
}
