package com.sharkpay.payouts.service;

import com.sharkpay.money.Money;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.ports.LedgerPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ledger journal-entry builder shared by every payout use-case — one place
 * owns the account chart and the posting keys, so the money-shape rules are
 * consistent by construction:
 *
 * <ul>
 *   <li><b>hold</b> ({@code payouts:{id}:hold}): debit principal wallet
 *       (amount + fee) → credit payouts-clearing (amount + fee) — funds
 *       control before provider initiation;</li>
 *   <li><b>settle</b> ({@code payouts:{id}:settle}): debit payouts-clearing
 *       (amount + fee) → credit payouts-rail (amount) + payouts-fees
 *       (fee, only when non-zero) — recognised on SUCCEEDED;</li>
 *   <li><b>return compensation</b> ({@code payouts:{id}:return}, reversal
 *       entry): debit the funds source (rail when captured, clearing when
 *       not) for the returned amount → credit principal wallet
 *       (returned − non-refundable fee) + payouts-fees (non-refundable
 *       fee) — exact integer math, never a history mutation;</li>
 *   <li><b>hold release</b> ({@code payouts:{id}:release}): strict inverse
 *       of the hold entry via {@link LedgerPort#reverse} — full refund of
 *       amount + fee (money never left).</li>
 * </ul>
 *
 * <p>Every posting is a single atomic journal entry with ≥ 2 legs,
 * idempotent on its key (the fake ledger enforces all of this; the real
 * ledger does it under row locks).</p>
 */
final class PayoutMoney {

    /** Clearing liability account (funds held for an in-flight payout). */
    static String clearingAccount(String currency) {
        return "payouts-clearing:" + currency;
    }

    /** Fee revenue account (non-refundable portions are retained here). */
    static String feesAccount(String currency) {
        return "payouts-fees:" + currency;
    }

    /** Rail settlement account (money recognised as sent to the rail). */
    static String railAccount(String currency) {
        return "payouts-rail:" + currency;
    }

    /** The principal wallet's ledger account ref. */
    static String walletAccount(Payout payout) {
        return payout.walletLedgerAccountId().toString();
    }

    static String holdKey(Payout payout) {
        return "payouts:" + payout.id() + ":hold";
    }

    static String settleKey(Payout payout) {
        return "payouts:" + payout.id() + ":settle";
    }

    static String returnKey(Payout payout) {
        return "payouts:" + payout.id() + ":return";
    }

    static String releaseKey(Payout payout) {
        return "payouts:" + payout.id() + ":release";
    }

    static String submitKey(Payout payout) {
        return "payouts:" + payout.id() + ":submit";
    }

    /** The hold entry: wallet → clearing for amount + fee (2 legs). */
    static LedgerPort.LedgerPosting holdEntry(Payout payout) {
        Money total = payout.amount().add(payout.fee());
        return LedgerPort.LedgerPosting.of(holdKey(payout), LedgerPort.Source.PAYOUTS,
                payout.internalRef(), LedgerPort.EntryType.HOLD,
                "payout hold " + payout.destination().describe(),
                List.of(new LedgerPort.Leg(walletAccount(payout), LedgerPort.Direction.DEBIT, total),
                        new LedgerPort.Leg(clearingAccount(payout.amount().currency()),
                                LedgerPort.Direction.CREDIT, total)));
    }

    /** The settle (capture) entry: clearing → rail + fees (2-3 legs). */
    static LedgerPort.LedgerPosting settleEntry(Payout payout) {
        Money total = payout.amount().add(payout.fee());
        String currency = payout.amount().currency();
        List<LedgerPort.Leg> legs = new ArrayList<>();
        legs.add(new LedgerPort.Leg(clearingAccount(currency), LedgerPort.Direction.DEBIT, total));
        legs.add(new LedgerPort.Leg(railAccount(currency), LedgerPort.Direction.CREDIT,
                payout.amount()));
        if (payout.fee().isPositive()) {
            legs.add(new LedgerPort.Leg(feesAccount(currency), LedgerPort.Direction.CREDIT,
                    payout.fee()));
        }
        return LedgerPort.LedgerPosting.of(settleKey(payout), LedgerPort.Source.PAYOUTS,
                payout.internalRef(), LedgerPort.EntryType.CAPTURE,
                "payout settled " + payout.destination().describe(), legs);
    }

    /**
     * The return compensation entry (reversal): debit the funds source
     * (rail when the settle entry exists, clearing otherwise) for the
     * returned amount, re-credit the principal wallet
     * {@code returned − nonRefundableFee} and retain the non-refundable fee
     * in the fee account. Zero-amount legs never post: when the full return
     * is retained ({@code returned == nonRefundableFee}) the entry is the
     * plain 2-leg source→fees reversal, and the wallet leg is dropped when
     * the re-credit is zero (journal legs must be strictly positive).
     */
    static LedgerPort.LedgerPosting returnCompensationEntry(Payout payout, Money returned,
                                                             String reason) {
        Objects.requireNonNull(payout, "payout is required");
        Objects.requireNonNull(returned, "returned is required");
        Money nonRefundable = payout.nonRefundableFee();
        long reversalMinor = returned.amountMinor() - nonRefundable.amountMinor();
        String currency = payout.amount().currency();
        String source = payout.settleEntryId() != null
                ? railAccount(currency)
                : clearingAccount(currency);
        UUID reverses = payout.settleEntryId() != null ? payout.settleEntryId()
                : payout.holdEntryId();
        List<LedgerPort.Leg> legs = new ArrayList<>();
        legs.add(new LedgerPort.Leg(source, LedgerPort.Direction.DEBIT, returned));
        if (reversalMinor > 0) {
            legs.add(new LedgerPort.Leg(walletAccount(payout), LedgerPort.Direction.CREDIT,
                    Money.of(reversalMinor, currency)));
        }
        if (nonRefundable.isPositive()) {
            legs.add(new LedgerPort.Leg(feesAccount(currency), LedgerPort.Direction.CREDIT,
                    nonRefundable));
        }
        return LedgerPort.LedgerPosting.reversalOf(returnKey(payout), LedgerPort.Source.PAYOUTS,
                payout.internalRef(), reverses,
                "payout returned: " + reason, legs);
    }
}
