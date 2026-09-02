package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PollPayoutsUseCase — in-flight polling applies outcomes through the SAME
 * result-application core as callbacks (poll and callback cannot disagree
 * about money); poll read failures skip the payout for the next tick
 * (never retry the debit); terminal races skip cleanly.
 */
class PollPayoutsUseCaseTest {

    @Test
    void pollsApplyScriptedProviderStatusesThroughTheSharedCore() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = submitted(env);
        env.gateway.statusFor(providerRef(payout), ProviderGatewayPort.ProviderStatus.SUCCEEDED);

        PollPayoutsUseCase.Report report = env.pollInFlight.pollInFlight();

        assertThat(report.inFlight()).isEqualTo(1);
        assertThat(report.evaluated()).isEqualTo(1);
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.SUCCEEDED);
        assertThat(env.ledger.effectCount("payouts:" + payout.id() + ":settle")).isEqualTo(1);
        assertThat(env.gateway.polls()).hasSize(1);
        assertThat(env.gateway.polls().get(0).ref()).isEqualTo(providerRef(payout).ref());
    }

    @Test
    void aReturnSurfacedByPollingDefaultsToTheFullAmount() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        // in-flight SENT payout — polling is the surface where returns show up
        Payout payout = sent(env);
        env.gateway.statusFor(providerRef(payout), ProviderGatewayPort.ProviderStatus.RETURNED);

        env.pollInFlight.pollInFlight();

        Payout returned = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(returned.state()).isEqualTo(PayoutState.RETURNED);
        // full payout amount compensated: 500000 − 5500 re-credited
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE - 510_500 + 494_500);
    }

    @Test
    void aPollReadFailureSkipsThePayoutWithoutTouchingMoney() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = submitted(env);
        env.gateway.failPollOf(providerRef(payout));

        PollPayoutsUseCase.Report report = env.pollInFlight.pollInFlight();

        assertThat(report.inFlight()).isEqualTo(1);
        assertThat(report.evaluated()).isZero();
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PROCESSING);
        assertThat(env.ledger.journal()).hasSize(1); // the hold only
        assertThat(env.gateway.initiateAttemptsFor(payout.id())).isEqualTo(1); // no debit retried
    }

    @Test
    void aRacedTerminalPayoutIsSkippedWithoutASecondEffect() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = settled(env); // SUCCEEDED before the poll batch ran
        env.gateway.statusFor(providerRef(payout), ProviderGatewayPort.ProviderStatus.RETURNED);

        PollPayoutsUseCase.Report report = env.pollInFlight.pollInFlight();

        assertThat(report.inFlight()).isZero(); // not in-flight anymore
        assertThat(report.evaluated()).isZero();
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.SUCCEEDED);
        assertThat(env.ledger.effectCount("payouts:" + payout.id() + ":return")).isZero();
    }

    @Test
    void thePollBatchIsBoundedAndOldestFirst() {
        PayoutsTestEnv env = new PayoutsTestEnv(50, 8);
        Payout first = submitted(env);
        env.clock.advance(Duration.ofSeconds(10));
        Payout second = submitted(env);
        var twoAtATime = new PollPayoutsUseCase(env.payouts, env.gateway, env.providerResults, 1);

        PollPayoutsUseCase.Report report = twoAtATime.pollInFlight();

        assertThat(report.inFlight()).isEqualTo(1);
        assertThat(report.evaluated()).isEqualTo(1);
        assertThat(env.gateway.polls()).hasSize(1);
        assertThat(env.gateway.polls().get(0).ref()).isEqualTo(providerRef(first).ref());
        // the second is polled on the next tick
        assertThat(twoAtATime.pollInFlight().evaluated()).isEqualTo(1);
        assertThat(env.payouts.findById(second.id()).orElseThrow().state())
                .isEqualTo(PayoutState.SENT);
    }

    @Test
    void constructionValidatesItsArguments() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        assertThatThrownBy(() -> new PollPayoutsUseCase(env.payouts, env.gateway,
                env.providerResults, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size must be positive");
        assertThatThrownBy(() -> new PollPayoutsUseCase(env.payouts, env.gateway, null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    private static ProviderGatewayPort.ProviderRef providerRef(Payout payout) {
        return ExpirePayoutsUseCase.providerRefOf(payout);
    }

    private static Payout submitted(PayoutsTestEnv env) {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        Payout loaded = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(loaded.state()).as("fixture must be PROCESSING").isEqualTo(
                PayoutState.PROCESSING);
        return loaded;
    }

    private static Payout sent(PayoutsTestEnv env) {
        Payout payout = submitted(env);
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.PENDING,
                null, null, null, null, null); // PROCESSING → SENT
        return env.payouts.findById(payout.id()).orElseThrow();
    }

    private static Payout settled(PayoutsTestEnv env) {
        Payout payout = submitted(env);
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);
        return env.payouts.findById(payout.id()).orElseThrow();
    }
}
