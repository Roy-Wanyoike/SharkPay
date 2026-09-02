package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.events.PayoutEvents;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReleaseDuePayoutsUseCase — G2 batch release exactness: due payouts are
 * released execute-after ascending in bounded batches, each via exactly one
 * provider submission; submission failures back off with the bounded
 * schedule; after maxAttempts total attempts the payout terminates FAILED
 * with the hold released — never an infinite retry.
 */
class ReleaseDuePayoutsUseCaseTest {

    @Test
    void duePayoutsAreReleasedInExecuteAfterOrderThroughTheGateway() {
        PayoutsTestEnv env = new PayoutsTestEnv(2, 8);
        Payout early = env.createPayout.create("k1", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null,
                PayoutsTestEnv.START.plusSeconds(10)).payout();
        Payout late = env.createPayout.create("k2", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null,
                PayoutsTestEnv.START.plusSeconds(30)).payout();
        Payout third = env.createPayout.create("k3", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null,
                PayoutsTestEnv.START.plusSeconds(50)).payout();

        env.clock.advance(Duration.ofSeconds(60));
        ReleaseDuePayoutsUseCase.Report first = env.releaseDue.releaseDue();

        // batch size 2: exactly the two earliest, execute-after ascending
        assertThat(first.considered()).isEqualTo(2);
        assertThat(first.submitted()).isEqualTo(2);
        assertThat(env.payouts.findById(early.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PROCESSING);
        assertThat(env.payouts.findById(late.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PROCESSING);
        assertThat(env.payouts.findById(third.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PENDING_RISK);
        assertThat(env.gateway.initiations()).extracting(
                        com.sharkpay.payouts.ports.ProviderGatewayPort.InitiateSubmission::payoutId)
                .containsExactly(early.id(), late.id());
        assertThat(env.gateway.initiateAttemptsFor(early.id())).isEqualTo(1); // exactly once
        assertThat(env.events.eventsOfType(PayoutEvents.PROCESSING)).hasSize(2);
        // the submission carries the exact payout shape and the idempotency key
        var submission = env.gateway.initiations().get(0);
        assertThat(submission.transactionKey()).isEqualTo("payouts:" + early.id() + ":submit");
        assertThat(submission.rail()).isEqualTo("mpesa");
        assertThat(submission.amountMinor()).isEqualTo(1_000L);
        assertThat(submission.currency()).isEqualTo("KES");
        assertThat(submission.exponent()).isEqualTo(2);
        assertThat(submission.destination()).isEqualTo(early.destination());
        // provider ref recorded + transition row
        Payout released = env.payouts.findById(early.id()).orElseThrow();
        assertThat(released.providerRef()).startsWith("honeycoin:hc_");
        assertThat(env.payouts.transitionsOf(early.id())).hasSize(2);
        // the journal still holds ONLY the hold entries — submission moves no money
        assertThat(env.ledger.journal()).hasSize(3);

        // second tick drains the rest
        ReleaseDuePayoutsUseCase.Report second = env.releaseDue.releaseDue();
        assertThat(second.considered()).isEqualTo(1);
        assertThat(second.submitted()).isEqualTo(1);
        assertThat(env.payouts.findById(third.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PROCESSING);
    }

    @Test
    void notYetDueAndBackedOffPayoutsAreNotConsidered() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout scheduled = env.createPayout.create("k1", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null,
                PayoutsTestEnv.START.plusSeconds(600)).payout();

        ReleaseDuePayoutsUseCase.Report report = env.releaseDue.releaseDue();
        assertThat(report.considered()).isZero();
        assertThat(env.payouts.findById(scheduled.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PENDING_RISK);

        // a payout in backoff is not due until nextAttemptAt passes
        Payout backedOff = env.payouts.findById(scheduled.id()).orElseThrow();
        backedOff.recordSubmitFailure(env.clock.instant().plusSeconds(3_000), env.clock.instant());
        env.payouts.save(backedOff);
        env.clock.advance(Duration.ofSeconds(700)); // executeAfter passed, backoff not
        assertThat(env.releaseDue.releaseDue().considered()).isZero();
    }

    @Test
    void aSubmissionFailureParksThePayoutWithTheExactBackoffDelay() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = env.createPayout("k1");
        env.gateway.failInitiateFor(payout.id(), 1);

        ReleaseDuePayoutsUseCase.Report report = env.releaseDue.releaseDue();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.submitted()).isZero();
        assertThat(report.retried()).isEqualTo(1);
        Payout parked = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(parked.state()).isEqualTo(PayoutState.PENDING_RISK); // retry, not a transition
        assertThat(parked.attempts()).isEqualTo(1);
        // backoff base 1s, no jitter → nextAttemptAt = now + 1s exactly
        assertThat(parked.nextAttemptAt()).isEqualTo(PayoutsTestEnv.START.plusSeconds(1));
        assertThat(env.events.eventsOfType(PayoutEvents.PROCESSING)).isEmpty();
        assertThat(env.payouts.transitionsOf(payout.id())).hasSize(1); // no audit row for retry
        // no money moved
        assertThat(env.ledger.journal()).hasSize(1); // the hold only
    }

    @Test
    void theBackoffWindowBlocksReleaseUntilItPasses() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        Payout payout = env.createPayout("k1");
        env.gateway.failInitiateFor(payout.id(), 1);
        env.releaseDue.releaseDue();

        env.clock.advance(Duration.ofMillis(999));
        assertThat(env.releaseDue.releaseDue().considered()).isZero();
        env.clock.advance(Duration.ofMillis(1));
        assertThat(env.releaseDue.releaseDue().submitted()).isEqualTo(1);
        assertThat(env.gateway.initiateAttemptsFor(payout.id())).isEqualTo(2);
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PROCESSING);
    }

    @Test
    void retriesAreBoundedByMaxAttemptsAndThenFailTerminallyWithTheHoldReleased() {
        PayoutsTestEnv env = new PayoutsTestEnv(50, 3);
        Payout payout = env.createPayout("k1");
        env.gateway.failInitiateFor(payout.id(), Integer.MAX_VALUE); // always down

        env.releaseDue.releaseDue();                              // attempt 1 → backoff
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();                              // attempt 2 → backoff
        env.clock.advance(Duration.ofSeconds(2));
        ReleaseDuePayoutsUseCase.Report terminal = env.releaseDue.releaseDue(); // attempt 3 → FAIL

        assertThat(terminal.considered()).isEqualTo(1);
        assertThat(terminal.failedTerminal()).isEqualTo(1);
        Payout failed = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(failed.state()).isEqualTo(PayoutState.FAILED);
        assertThat(failed.failureReason()).isEqualTo(
                ReleaseDuePayoutsUseCase.MAX_ATTEMPTS_REASON);
        // exactly maxAttempts gateway attempts in total — no infinite retry
        assertThat(env.gateway.initiateAttemptsFor(payout.id())).isEqualTo(3);
        // the hold was released: strict reversal of the hold entry
        var release = env.ledger.entry("payouts:" + payout.id() + ":release").orElseThrow();
        assertThat(release.entryType()).isEqualTo(LedgerPort.EntryType.REVERSAL);
        assertThat(release.reversesEntryId()).isEqualTo(failed.holdEntryId());
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE); // money fully back
        assertThat(env.ledger.balanceOf("payouts-clearing:KES", "KES")).isZero();
        // exactly one terminal failed event
        assertThat(env.events.eventsOfType(PayoutEvents.FAILED)).hasSize(1);

        // further ticks never touch the terminal payout again
        env.clock.advance(Duration.ofSeconds(600));
        ReleaseDuePayoutsUseCase.Report after = env.releaseDue.releaseDue();
        assertThat(after.considered()).isZero();
        assertThat(env.gateway.initiateAttemptsFor(payout.id())).isEqualTo(3);
    }

    @Test
    void anAttemptCounterAlreadyAtTheBoundTerminatesWithoutAnotherSubmission() {
        // the crash-between-failure-and-save defensive branch
        PayoutsTestEnv env = new PayoutsTestEnv(50, 2);
        Payout payout = env.createPayout("k1");
        Payout loaded = env.payouts.findById(payout.id()).orElseThrow();
        loaded.recordSubmitFailure(env.clock.instant(), env.clock.instant()); // attempts = 1
        loaded.recordSubmitFailure(env.clock.instant(), env.clock.instant()); // attempts = 2 = bound
        env.payouts.save(loaded);

        ReleaseDuePayoutsUseCase.Report report = env.releaseDue.releaseDue();

        assertThat(report.failedTerminal()).isEqualTo(1);
        assertThat(env.gateway.initiateAttemptsFor(payout.id())).isZero(); // no new attempt
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.FAILED);
    }

    @Test
    void retryThenSuccessFlowsThroughTheSameIdempotentSubmissionKey() {
        PayoutsTestEnv env = new PayoutsTestEnv(50, 8);
        Payout payout = env.createPayout("k1");
        env.gateway.failInitiateFor(payout.id(), 1);

        env.releaseDue.releaseDue(); // failure → backoff
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue(); // retry succeeds

        assertThat(env.gateway.initiateAttemptsFor(payout.id())).isEqualTo(2);
        assertThat(env.gateway.initiateEffectsFor("payouts:" + payout.id() + ":submit"))
                .isEqualTo(1); // one rail effect under the same key
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PROCESSING);
    }

    @Test
    void aHoldReleaseRejectionSurfacesAsALedgerPostingException() {
        PayoutsTestEnv env = new PayoutsTestEnv(50, 1);
        Payout payout = env.createPayout("k1");
        env.gateway.failInitiateFor(payout.id(), 1);
        env.ledger.reject("payouts:" + payout.id() + ":release", "balance_invariant",
                "cannot reverse");

        assertThatThrownBy(() -> env.releaseDue.releaseDue())
                .isInstanceOf(com.sharkpay.payouts.domain.LedgerPostingException.class)
                .hasMessageContaining("payouts:" + payout.id() + ":release")
                .hasMessageContaining("parked for ops");
    }

    @Test
    void constructionValidatesBatchSizeAndMaxAttempts() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        assertThatThrownBy(() -> new ReleaseDuePayoutsUseCase(env.payouts, env.gateway, env.ledger,
                env.events, env.backoff, env.randomness, env.clock, 0, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size must be positive");
        assertThatThrownBy(() -> new ReleaseDuePayoutsUseCase(env.payouts, env.gateway, env.ledger,
                env.events, env.backoff, env.randomness, env.clock, 50, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max attempts must be positive");
        assertThatThrownBy(() -> new ReleaseDuePayoutsUseCase(env.payouts, env.gateway, env.ledger,
                env.events, null, env.randomness, env.clock, 50, 8))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReleaseDuePayoutsUseCase(env.payouts, env.gateway, env.ledger,
                env.events, env.backoff, null, env.clock, 50, 8))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theReportCarriesTheExactTickNumbers() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        env.createPayout("k1");
        ReleaseDuePayoutsUseCase.Report report = env.releaseDue.releaseDue();
        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.submitted()).isEqualTo(1);
        assertThat(report.retried()).isZero();
        assertThat(report.failedTerminal()).isZero();
    }
}
