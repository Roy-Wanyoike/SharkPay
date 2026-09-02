package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ExpirePayoutsUseCase — the TTL sweep: payouts past {@code expiresAt}
 * that the provider has not accepted are auto-cancelled with their hold
 * released; a PROCESSING payout is cancelled at the provider FIRST and
 * parked on refusal (never force-cancel in-flight money).
 */
class ExpirePayoutsUseCaseTest {

    @Test
    void anExpiredPendingRiskPayoutIsCancelledAndItsHoldReleased() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = env.createDefaultPayout(); // expires at START+900

        env.clock.advance(Duration.ofSeconds(901));
        ExpirePayoutsUseCase.Report report = env.expireOverdue.expireOverdue();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.cancelled()).isEqualTo(1);
        Payout cancelled = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(cancelled.state()).isEqualTo(PayoutState.CANCELLED);
        // system-actor TTL expiry row
        var transition = env.payouts.transitionsOf(payout.id()).get(1);
        assertThat(transition.from()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(transition.to()).isEqualTo(PayoutState.CANCELLED);
        assertThat(transition.trigger()).isEqualTo("expiry");
        assertThat(transition.actor()).isEqualTo("system");
        // hold released in full
        var release = env.ledger.entry("payouts:" + payout.id() + ":release").orElseThrow();
        assertThat(release.entryType()).isEqualTo(LedgerPort.EntryType.REVERSAL);
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE);
        assertThat(env.ledger.balanceOf("payouts-clearing:KES", "KES")).isZero();
        assertThat(env.gateway.cancellations()).isEmpty(); // never submitted → no provider cancel
    }

    @Test
    void anExpiredProcessingPayoutIsCancelledAtTheProviderFirst() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = submitted(env);
        env.gateway.defaultStatus(ProviderGatewayPort.ProviderStatus.PENDING); // not settled

        env.clock.advance(Duration.ofSeconds(901));
        ExpirePayoutsUseCase.Report report = env.expireOverdue.expireOverdue();

        assertThat(report.cancelled()).isEqualTo(1);
        assertThat(env.gateway.cancellations()).hasSize(1);
        assertThat(env.gateway.cancellations().get(0).ref())
                .isEqualTo(providerRefOf(payout).ref());
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.CANCELLED);
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE);
    }

    @Test
    void aProviderCancellationRefusalParksThePayoutForTheNextSweep() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = submitted(env);
        env.gateway.refuseCancelOf(providerRefOf(payout));

        env.clock.advance(Duration.ofSeconds(901));
        ExpirePayoutsUseCase.Report report = env.expireOverdue.expireOverdue();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.cancelled()).isZero(); // parked
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PROCESSING);
        assertThat(env.ledger.journal()).hasSize(1); // hold untouched — money never force-cancelled
        assertThat(env.payouts.transitionsOf(payout.id())).hasSize(2); // no CANCELLED row

        // a later sweep where the provider accepts cancels it
        env.expireOverdue.expireOverdue();
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.CANCELLED);
    }

    @Test
    void nonExpiredPayoutsAreNotConsidered() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        env.createDefaultPayout(); // expires at START+900

        env.clock.advance(Duration.ofSeconds(900)); // expiresAt.isBefore(now) is false
        assertThat(env.expireOverdue.expireOverdue().considered()).isZero();

        env.clock.advance(Duration.ofSeconds(2));
        assertThat(env.expireOverdue.expireOverdue().considered()).isEqualTo(1);
    }

    @Test
    void terminalPayoutsAreNeverSwept() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = submitted(env);
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);

        env.clock.advance(Duration.ofSeconds(10_000));
        assertThat(env.expireOverdue.expireOverdue().considered()).isZero();
    }

    @Test
    void theSweepIsBoundedByTheBatchSize() {
        PayoutsTestEnv env = new PayoutsTestEnv(50, 8);
        for (int i = 1; i <= 3; i++) {
            env.createPayout.create("k" + i, PayoutsTestEnv.WALLET, 1_000L, "KES",
                    PayoutsTestEnv.mpesaDestination(), null, Map.of(), 60, null);
        }
        env.clock.advance(Duration.ofSeconds(61));
        var twoAtATime = new ExpirePayoutsUseCase(env.payouts, env.gateway, env.ledger,
                env.clock, 2);
        assertThat(twoAtATime.expireOverdue().cancelled()).isEqualTo(2);
        assertThat(twoAtATime.expireOverdue().cancelled()).isEqualTo(1); // drains the rest
    }

    @Test
    void constructionValidatesItsArguments() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        assertThatThrownBy(() -> new ExpirePayoutsUseCase(env.payouts, env.gateway, env.ledger,
                env.clock, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size must be positive");
        assertThatThrownBy(() -> new ExpirePayoutsUseCase(null, env.gateway, env.ledger,
                env.clock, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExpirePayoutsUseCase(env.payouts, env.gateway, env.ledger,
                null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void providerRefOfParsesTheCompositeRefAndRejectsMalformedOnes() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = env.fixturePayout("honeycoin:hc_42");
        ProviderGatewayPort.ProviderRef ref = ExpirePayoutsUseCase.providerRefOf(payout);
        assertThat(ref.provider()).isEqualTo("honeycoin");
        assertThat(ref.ref()).isEqualTo("hc_42");

        assertThatThrownBy(() -> ExpirePayoutsUseCase.providerRefOf(env.fixturePayout(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no provider ref");
        assertThatThrownBy(() -> ExpirePayoutsUseCase.providerRefOf(env.fixturePayout("")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExpirePayoutsUseCase.providerRefOf(env.fixturePayout(
                "nocolon")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed provider ref");
        assertThatThrownBy(() -> ExpirePayoutsUseCase.providerRefOf(env.fixturePayout(
                "honeycoin:")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExpirePayoutsUseCase.providerRefOf(env.fixturePayout(
                ":hc_1")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ProviderGatewayPort.ProviderRef providerRefOf(Payout payout) {
        return ExpirePayoutsUseCase.providerRefOf(payout);
    }

    /** Pushes an accepted payout to PROCESSING through one release tick. */
    private static Payout submitted(PayoutsTestEnv env) {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        Payout loaded = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(loaded.state()).as("fixture must be PROCESSING").isEqualTo(
                PayoutState.PROCESSING);
        return loaded;
    }
}
