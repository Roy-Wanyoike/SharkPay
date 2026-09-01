package com.sharkpay.wallet.service;

import com.sharkpay.money.Money;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.wallet.domain.Balances;
import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.ports.HoldRepository;
import com.sharkpay.wallet.ports.ProjectionStore;

import java.util.List;
import java.util.Objects;

/**
 * Computes a wallet's three balance partitions from the projection and the
 * hold ledger:
 *
 * <ul>
 *   <li>{@code total} — ledger projection balance (sole authority: applied
 *       ledger.posting.committed.v1 legs);</li>
 *   <li>{@code held} — sum of the wallet's ACTIVE holds (integer minor-unit
 *       addition with explicit overflow rejection);</li>
 *   <li>{@code pending} — zero in V1;</li>
 *   <li>{@code available = total - held} — spendable now.</li>
 * </ul>
 */
public final class BalanceReader {

    private final ProjectionStore projections;
    private final HoldRepository holds;

    public BalanceReader(ProjectionStore projections, HoldRepository holds) {
        this.projections = Objects.requireNonNull(projections, "projectionStore is required");
        this.holds = Objects.requireNonNull(holds, "holdRepository is required");
    }

    /** The wallet's current balance partitions. */
    public Balances balancesOf(Wallet wallet) {
        Objects.requireNonNull(wallet, "wallet is required");
        String currency = wallet.currency();
        Money total = Money.of(projections.totalMinor(wallet.id()), currency);
        Money pending = Money.zero(currency);
        Money held = Money.zero(currency);
        List<Hold> active = holds.findActiveByWalletId(wallet.id());
        for (Hold hold : active) {
            try {
                held = held.add(hold.amount());
            } catch (ArithmeticException overflow) {
                throw new MoneyOverflowException("held-balance overflow on wallet " + wallet.id(), overflow);
            }
        }
        return new Balances(total, held, pending);
    }
}
