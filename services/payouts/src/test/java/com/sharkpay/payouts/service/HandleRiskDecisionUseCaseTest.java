package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HandleRiskDecisionUseCase — the BLOCKED intake of
 * docs/STATE-MACHINES.md §2: a DENY on a PENDING_RISK payout blocks it
 * before submission and releases the hold in full; ALLOW is a no-op; a
 * late DENY is a 409.
 */
class HandleRiskDecisionUseCaseTest {

    private final PayoutsTestEnv env = new PayoutsTestEnv();

    @Test
    void aDenyOnAPendingRiskPayoutBlocksItAndReleasesTheHold() {
        Payout payout = env.createDefaultPayout();

        Payout blocked = env.riskDecisions.apply(payout.id(), "DENY", "velocity anomaly");

        assertThat(blocked.state()).isEqualTo(PayoutState.BLOCKED);
        assertThat(blocked.isTerminal()).isTrue();
        var transition = env.payouts.transitionsOf(payout.id()).get(1);
        assertThat(transition.from()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(transition.to()).isEqualTo(PayoutState.BLOCKED);
        assertThat(transition.trigger()).isEqualTo("risk");
        assertThat(transition.actor()).isEqualTo("risk");
        assertThat(transition.note()).isEqualTo("velocity anomaly");
        var release = env.ledger.entry("payouts:" + payout.id() + ":release").orElseThrow();
        assertThat(release.entryType()).isEqualTo(LedgerPort.EntryType.REVERSAL);
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE);
        assertThat(env.ledger.balanceOf("payouts-clearing:KES", "KES")).isZero();
    }

    @Test
    void theDecisionIsCaseInsensitiveAndABlankReasonDefaults() {
        Payout payout = env.createPayout("k-ci-1");
        assertThat(env.riskDecisions.apply(payout.id(), "deny", null).state())
                .isEqualTo(PayoutState.BLOCKED);

        Payout other = env.createPayout("k-ci-2");
        assertThat(env.riskDecisions.apply(other.id(), " DenY ", "  ").state())
                .isEqualTo(PayoutState.BLOCKED);
        assertThat(env.payouts.transitionsOf(other.id()).get(1).note()).isEqualTo("risk denied");
    }

    @Test
    void anAllowVerdictLeavesThePayoutScheduled() {
        Payout payout = env.createDefaultPayout();
        Payout allowed = env.riskDecisions.apply(payout.id(), "ALLOW", "looks fine");
        assertThat(allowed.state()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(env.ledger.journal()).hasSize(1); // hold untouched
        assertThat(env.payouts.transitionsOf(payout.id())).hasSize(1); // no transition row
    }

    @Test
    void aDenyOnACreatedPayoutBlocksItWithoutALedgerTouch() {
        // a CREATED payout that never got a hold (direct domain assembly)
        Payout unheld = new Payout("pot_0123456789abcdef0123456789abcdee",
                java.util.UUID.randomUUID(), PayoutsTestEnv.WALLET, env.walletAccount,
                com.sharkpay.money.Money.of(1_000, "KES"),
                com.sharkpay.money.Money.zero("KES"),
                com.sharkpay.money.Money.zero("KES"), com.sharkpay.payouts.domain.Rail.MPESA,
                PayoutsTestEnv.mpesaDestination(), PayoutState.CREATED, null, null, null, 0, null,
                null, PayoutsTestEnv.START.plusSeconds(900), null, null, null, java.util.Map.of(),
                PayoutsTestEnv.START, PayoutsTestEnv.START, java.util.List.of());
        env.payouts.save(unheld);
        int journalBefore = env.ledger.journal().size();

        Payout blocked = env.riskDecisions.apply(unheld.id(), "DENY", "sanctions");

        assertThat(blocked.state()).isEqualTo(PayoutState.BLOCKED);
        assertThat(env.payouts.transitionsOf(unheld.id()).get(0).from()).isEqualTo(
                PayoutState.CREATED);
        assertThat(env.ledger.journal()).hasSize(journalBefore); // isHeld() false → no reversal
    }

    @Test
    void aDenyArrivingAfterPayoutLeftPendingRiskIsA409() {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue(); // → PROCESSING

        assertThatThrownBy(() -> env.riskDecisions.apply(payout.id(), "DENY", "late"))
                .isInstanceOf(com.sharkpay.payouts.domain.RiskDeniedException.class)
                .hasMessageContaining("PROCESSING")
                .hasMessageContaining("only PENDING_RISK");
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PROCESSING);
        assertThat(env.ledger.journal()).hasSize(1); // untouched
    }

    @Test
    void anUnknownVerdictIsRejected() {
        Payout payout = env.createDefaultPayout();
        assertThatThrownBy(() -> env.riskDecisions.apply(payout.id(), "MAYBE", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ALLOW or DENY");
    }

    @Test
    void anUnknownPayoutIsA404AndArgumentsAreNullChecked() {
        assertThatThrownBy(() -> env.riskDecisions.apply("pot_0000000000000000000000000", "ALLOW",
                null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");
        Payout payout = env.createDefaultPayout();
        assertThatThrownBy(() -> env.riskDecisions.apply(null, "ALLOW", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> env.riskDecisions.apply(payout.id(), null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aReleaseRejectionSurfacesAsALedgerPostingException() {
        Payout payout = env.createDefaultPayout();
        env.ledger.reject("payouts:" + payout.id() + ":release", "balance_invariant",
                "cannot reverse");
        assertThatThrownBy(() -> env.riskDecisions.apply(payout.id(), "DENY", "r"))
                .isInstanceOf(com.sharkpay.payouts.domain.LedgerPostingException.class);
    }
}
