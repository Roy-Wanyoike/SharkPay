package com.sharkpay.payouts.service;

import com.sharkpay.money.Money;
import com.sharkpay.payouts.domain.Destination;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.Rail;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PayoutMoney — the single place that owns the account chart and the
 * posting keys (money-shape rules consistent by construction). Every entry
 * must be ONE balanced atomic journal entry with ≥ 2 legs; the return
 * compensation must be exact integer arithmetic:
 * {@code re-credit = returned − non_refundable_fee} with the non-refundable
 * portion retained in the fees account, and never a zero-amount leg (the
 * ledger rejects those).
 */
class PayoutMoneyTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final PayoutsTestEnv ENV = new PayoutsTestEnv();

    @Test
    void theHoldEntryIsOneBalancedTwoLegWalletToClearingPosting() {
        Payout payout = ENV.createDefaultPayout();

        LedgerPort.LedgerPosting hold = PayoutMoney.holdEntry(payout);
        assertThat(hold.transactionKey()).isEqualTo("payouts:" + payout.id() + ":hold");
        assertThat(hold.source()).isEqualTo(LedgerPort.Source.PAYOUTS);
        assertThat(hold.sourceRef()).isEqualTo(payout.internalRef());
        assertThat(hold.entryType()).isEqualTo(LedgerPort.EntryType.HOLD);
        assertThat(hold.reversesEntryId()).isNull();
        assertThat(hold.reason()).contains("payout hold").contains("mpesa:+254712345678");
        assertThat(hold.legs()).hasSize(2);
        assertThat(hold.legs().get(0).accountRef()).isEqualTo(payout.walletLedgerAccountId()
                .toString());
        assertThat(hold.legs().get(0).direction()).isEqualTo(LedgerPort.Direction.DEBIT);
        assertThat(hold.legs().get(0).amount()).isEqualTo(Money.of(510_500, "KES"));
        assertThat(hold.legs().get(1).accountRef()).isEqualTo("payouts-clearing:KES");
        assertThat(hold.legs().get(1).direction()).isEqualTo(LedgerPort.Direction.CREDIT);
        assertThat(hold.legs().get(1).amount()).isEqualTo(Money.of(510_500, "KES"));
        assertBalanced(hold);
    }

    @Test
    void theSettleEntrySplitsClearingIntoRailAndFees() {
        Payout payout = sentPayoutWithFee(500_000, 10_500, 5_500);

        LedgerPort.LedgerPosting settle = PayoutMoney.settleEntry(payout);
        assertThat(settle.transactionKey()).isEqualTo("payouts:" + payout.id() + ":settle");
        assertThat(settle.entryType()).isEqualTo(LedgerPort.EntryType.CAPTURE);
        assertThat(settle.legs()).hasSize(3);
        assertThat(settle.legs().get(0).accountRef()).isEqualTo("payouts-clearing:KES");
        assertThat(settle.legs().get(0).direction()).isEqualTo(LedgerPort.Direction.DEBIT);
        assertThat(settle.legs().get(0).amount()).isEqualTo(Money.of(510_500, "KES"));
        assertThat(settle.legs().get(1).accountRef()).isEqualTo("payouts-rail:KES");
        assertThat(settle.legs().get(1).amount()).isEqualTo(Money.of(500_000, "KES"));
        assertThat(settle.legs().get(2).accountRef()).isEqualTo("payouts-fees:KES");
        assertThat(settle.legs().get(2).amount()).isEqualTo(Money.of(10_500, "KES"));
        assertBalanced(settle);
    }

    @Test
    void aZeroFeeSettleIsAPlainTwoLegPosting() {
        Payout payout = sentPayoutWithFee(1_000, 0, 0);
        assertThat(PayoutMoney.settleEntry(payout).legs()).hasSize(2);
        assertThat(PayoutMoney.settleEntry(payout).legs().get(1).amount())
                .isEqualTo(Money.of(1_000, "KES"));
    }

    @Test
    void theAccountChartIsCurrencyScoped() {
        Payout usdc = onChainPayout();
        assertThat(PayoutMoney.clearingAccount("USDC")).isEqualTo("payouts-clearing:USDC");
        assertThat(PayoutMoney.feesAccount("USDC")).isEqualTo("payouts-fees:USDC");
        assertThat(PayoutMoney.railAccount("USDC")).isEqualTo("payouts-rail:USDC");
        assertThat(PayoutMoney.holdEntry(usdc).legs().get(1).accountRef())
                .isEqualTo("payouts-clearing:USDC");
        assertThat(PayoutMoney.walletAccount(usdc)).isEqualTo(usdc.walletLedgerAccountId()
                .toString());
    }

    @Test
    void theSubmitKeyIsStablePerPayout() {
        Payout payout = ENV.createDefaultPayout();
        assertThat(PayoutMoney.submitKey(payout)).isEqualTo("payouts:" + payout.id() + ":submit");
        assertThat(PayoutMoney.releaseKey(payout)).isEqualTo("payouts:" + payout.id()
                + ":release");
        assertThat(PayoutMoney.returnKey(payout)).isEqualTo("payouts:" + payout.id() + ":return");
    }

    @Test
    void theReturnCompensationReCreditsTheWalletMinusTheNonRefundableFee() {
        // captured payout: compensation debits the RAIL account and
        // references the settle entry
        Payout payout = capturedPayout(500_000, 10_500, 5_500);

        LedgerPort.LedgerPosting compensation = PayoutMoney.returnCompensationEntry(payout,
                Money.of(500_000, "KES"), "msisdn_not_registered");
        assertThat(compensation.transactionKey()).isEqualTo("payouts:" + payout.id() + ":return");
        assertThat(compensation.entryType()).isEqualTo(LedgerPort.EntryType.REVERSAL);
        assertThat(compensation.reversesEntryId()).isEqualTo(payout.settleEntryId());
        assertThat(compensation.reason()).contains("payout returned")
                .contains("msisdn_not_registered");
        assertThat(compensation.legs()).hasSize(3);
        assertThat(compensation.legs().get(0).accountRef()).isEqualTo("payouts-rail:KES");
        assertThat(compensation.legs().get(0).direction()).isEqualTo(LedgerPort.Direction.DEBIT);
        assertThat(compensation.legs().get(0).amount()).isEqualTo(Money.of(500_000, "KES"));
        assertThat(compensation.legs().get(1).accountRef()).isEqualTo(
                payout.walletLedgerAccountId().toString());
        assertThat(compensation.legs().get(1).amount()).isEqualTo(Money.of(494_500, "KES"));
        assertThat(compensation.legs().get(2).accountRef()).isEqualTo("payouts-fees:KES");
        assertThat(compensation.legs().get(2).amount()).isEqualTo(Money.of(5_500, "KES"));
        assertBalanced(compensation);
    }

    @Test
    void anUnsettledReturnCompensatesFromClearingAndReferencesTheHoldEntry() {
        Payout payout = sentPayoutWithFee(500_000, 10_500, 5_500);

        LedgerPort.LedgerPosting compensation = PayoutMoney.returnCompensationEntry(payout,
                Money.of(500_000, "KES"), "returned");
        assertThat(compensation.reversesEntryId()).isEqualTo(payout.holdEntryId());
        assertThat(compensation.legs().get(0).accountRef()).isEqualTo("payouts-clearing:KES");
        assertBalanced(compensation);
    }

    @Test
    void aZeroNonRefundableFeeCompensatesInFullWithTwoLegs() {
        Payout payout = sentPayoutWithFee(1_000, 0, 0);
        LedgerPort.LedgerPosting compensation = PayoutMoney.returnCompensationEntry(payout,
                Money.of(1_000, "KES"), "returned");
        assertThat(compensation.legs()).hasSize(2);
        assertThat(compensation.legs().get(1).amount()).isEqualTo(Money.of(1_000, "KES"));
        assertBalanced(compensation);
    }

    @Test
    void aPartialReturnCompensatesTheExactReturnedAmount() {
        Payout payout = capturedPayout(500_000, 10_500, 5_500);
        LedgerPort.LedgerPosting compensation = PayoutMoney.returnCompensationEntry(payout,
                Money.of(200_000, "KES"), "partial return");
        assertThat(compensation.legs().get(0).amount()).isEqualTo(Money.of(200_000, "KES"));
        assertThat(compensation.legs().get(1).amount()).isEqualTo(Money.of(194_500, "KES"));
        assertThat(compensation.legs().get(2).amount()).isEqualTo(Money.of(5_500, "KES"));
        assertBalanced(compensation);
    }

    @Test
    void aReturnEqualToTheNonRefundableFeePostsTwoLegsAndReCreditsNothing() {
        // returned == non-refundable fee: the wallet leg is zero and must be
        // OMITTED (journal legs must be positive) — the fees account retains
        // the whole returned amount
        Payout payout = capturedPayout(500_000, 10_500, 5_500);
        LedgerPort.LedgerPosting compensation = PayoutMoney.returnCompensationEntry(payout,
                Money.of(5_500, "KES"), "fee-only return");

        assertThat(compensation.legs()).hasSize(2);
        assertThat(compensation.legs().get(0).amount()).isEqualTo(Money.of(5_500, "KES"));
        assertThat(compensation.legs().get(1).accountRef()).isEqualTo("payouts-fees:KES");
        assertThat(compensation.legs().get(1).amount()).isEqualTo(Money.of(5_500, "KES"));
        assertBalanced(compensation);
    }

    @Test
    void theReturnCompensationRequiresBothArguments() {
        Payout payout = sentPayoutWithFee(1_000, 0, 0);
        assertThatThrownBy(() -> PayoutMoney.returnCompensationEntry(null,
                Money.of(1, "KES"), "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PayoutMoney.returnCompensationEntry(payout, null, "r"))
                .isInstanceOf(NullPointerException.class);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void assertBalanced(LedgerPort.LedgerPosting posting) {
        long net = 0;
        for (LedgerPort.Leg leg : posting.legs()) {
            net += leg.direction() == LedgerPort.Direction.CREDIT ? leg.amount().amountMinor()
                    : -leg.amount().amountMinor();
        }
        assertThat(net).as("entry %s must balance per currency", posting.transactionKey())
                .isZero();
        for (LedgerPort.Leg leg : posting.legs()) {
            assertThat(leg.amount().isPositive())
                    .as("leg amounts must be positive in %s", posting.transactionKey())
                    .isTrue();
        }
    }

    private static Payout sentPayoutWithFee(long amountMinor, long feeMinor,
                                            long nonRefundableMinor) {
        Payout payout = newPayout(amountMinor, feeMinor, nonRefundableMinor);
        payout.accept(T0, UUID.randomUUID(), T0);
        payout.markSubmitted("honeycoin:hc_1", T0);
        payout.markSent(T0);
        return payout;
    }

    private static Payout capturedPayout(long amountMinor, long feeMinor,
                                         long nonRefundableMinor) {
        Payout payout = sentPayoutWithFee(amountMinor, feeMinor, nonRefundableMinor);
        payout.markSucceeded(UUID.randomUUID(), T0);
        return payout;
    }

    private static Payout newPayout(long amountMinor, long feeMinor,
                                    long nonRefundableMinor) {
        return new Payout("pot_0123456789abcdef0123456789abcdef", UUID.randomUUID(),
                PayoutsTestEnv.WALLET, UUID.randomUUID(), Money.of(amountMinor, "KES"),
                Money.of(feeMinor, "KES"), Money.of(nonRefundableMinor, "KES"), Rail.MPESA,
                new Destination("mpesa", "+254712345678", null, null, null, null, null, null),
                PayoutState.CREATED, null, null, null, 0, null, null, T0.plusSeconds(900), null,
                null, null, null, T0, T0, List.of());
    }

    private static Payout onChainPayout() {
        return new Payout("pot_0123456789abcdef0123456789abcdee", UUID.randomUUID(),
                PayoutsTestEnv.WALLET, UUID.randomUUID(), Money.of(25_000_000, "USDC"),
                Money.of(312_500, "USDC"), Money.of(250_000, "USDC"), Rail.ON_CHAIN,
                new Destination("on_chain", null, null, null, null, null, "base",
                        "0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d"),
                PayoutState.CREATED, null, null, null, 0, null, null, T0.plusSeconds(900), null,
                null, null, null, T0, T0, List.of());
    }
}
