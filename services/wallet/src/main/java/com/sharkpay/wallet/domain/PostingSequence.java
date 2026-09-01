package com.sharkpay.wallet.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import com.sharkpay.money.MoneyOverflowException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The per-wallet posting sequence: the pure, order-defining core of the
 * ledger balance projection.
 *
 * <p><b>Sequence-ordered application.</b> Legs are keyed by the ledger's
 * globally monotonic {@code posting_id}. Totals and every
 * {@code balance_after} are recomputed by folding the legs in
 * {@code posting_id} order on every insert, so out-of-order event delivery
 * converges to exactly the same projection as in-order delivery.
 *
 * <p><b>Idempotence.</b> A leg whose {@code posting_id} is already present is
 * a no-op ({@link #apply} returns false) — duplicate event delivery can
 * never double-apply a leg.
 *
 * <p><b>Money safety.</b> The fold is pure {@code long} minor-unit math with
 * explicit overflow checks ({@link MoneyOverflowException} is wrapped into
 * {@link ProjectionInconsistencyException} so the offending event can be
 * rejected without partial state), and a leg that would drive the wallet
 * below zero is rejected — the ledger guarantees non-negative wallet
 * accounts, so a violation means a contract breach upstream.
 */
public final class PostingSequence {

    private final String walletId;
    private final String currency;
    private final NavigableMap<Long, ProjectionLeg> legsByPostingId = new TreeMap<>();
    private List<StatementLine> statement = List.of();
    private long totalMinor;

    public PostingSequence(String walletId, String currency) {
        this.walletId = Objects.requireNonNull(walletId, "walletId is required");
        this.currency = Objects.requireNonNull(currency, "currency is required");
    }

    /**
     * Applies one leg. Duplicate posting ids are ignored.
     *
     * @return true when the leg was newly applied, false when it was already
     *         present (duplicate delivery)
     * @throws ProjectionInconsistencyException on currency mismatch,
     *         overflow or a below-zero running balance; the sequence is left
     *         unchanged when it throws
     */
    public boolean apply(ProjectionLeg leg) {
        Objects.requireNonNull(leg, "leg is required");
        if (!leg.amount().currency().equals(currency)) {
            throw new ProjectionInconsistencyException("leg " + leg.postingId() + " currency "
                    + leg.amount().currency() + " does not match wallet " + walletId
                    + " currency " + currency);
        }
        if (legsByPostingId.containsKey(leg.postingId())) {
            return false;
        }
        NavigableMap<Long, ProjectionLeg> next = new TreeMap<>(legsByPostingId);
        next.put(leg.postingId(), leg);
        List<StatementLine> recomputed = fold(next);   // throws before any mutation
        legsByPostingId.put(leg.postingId(), leg);
        statement = recomputed;
        totalMinor = recomputed.isEmpty() ? 0L : recomputed.get(recomputed.size() - 1).balanceAfter().amountMinor();
        return true;
    }

    private List<StatementLine> fold(NavigableMap<Long, ProjectionLeg> orderedLegs) {
        List<StatementLine> lines = new ArrayList<>(orderedLegs.size());
        long running = 0L;
        for (ProjectionLeg leg : orderedLegs.values()) {
            long delta = leg.amount().amountMinor();
            try {
                running = leg.direction() == Direction.CREDIT
                        ? Math.addExact(running, delta)
                        : Math.subtractExact(running, delta);
            } catch (ArithmeticException overflow) {
                throw new ProjectionInconsistencyException(
                        "wallet " + walletId + " balance overflows int64 minor units at posting "
                                + leg.postingId(), new MoneyOverflowException("projection overflow", overflow));
            }
            if (running < 0L) {
                throw new ProjectionInconsistencyException("wallet " + walletId
                        + " would project a negative balance (" + running + ") at posting "
                        + leg.postingId() + "; the ledger guarantees wallet accounts never go negative");
            }
            lines.add(new StatementLine(leg, Money.of(running, currency)));
        }
        return List.copyOf(lines);
    }

    /** The wallet's total (ledger) balance in minor units. */
    public long totalMinor() {
        return totalMinor;
    }

    /** The total balance as {@link Money}. */
    public Money total() {
        return Money.of(totalMinor, currency);
    }

    /** All statement lines in posting order, with running balances. */
    public List<StatementLine> statement() {
        return statement;
    }

    /** Number of applied legs (for tests/observability). */
    public int size() {
        return legsByPostingId.size();
    }

    public String walletId() {
        return walletId;
    }

    public String currency() {
        return currency;
    }

    /** The BigInteger sum of credits minus debits (audit; never used in money paths). */
    BigInteger auditTotal() {
        BigInteger sum = BigInteger.ZERO;
        for (ProjectionLeg leg : legsByPostingId.values()) {
            sum = sum.add(BigInteger.valueOf(leg.direction() == Direction.CREDIT
                    ? leg.amount().amountMinor() : -leg.amount().amountMinor()));
        }
        return sum;
    }
}
