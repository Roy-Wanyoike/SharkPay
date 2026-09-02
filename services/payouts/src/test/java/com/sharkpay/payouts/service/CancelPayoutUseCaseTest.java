package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.IdempotencyConflictException;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CancelPayoutUseCase — user cancellation before provider acceptance
 * (CREATED/PENDING_RISK), full hold release, scheduler wake-up
 * cancellation and exact idempotency semantics; later states 409.
 */
class CancelPayoutUseCaseTest {

    private final PayoutsTestEnv env = new PayoutsTestEnv();

    @Test
    void cancellingAPendingRiskPayoutReleasesTheFullHold() {
        Payout payout = env.createDefaultPayout();

        CancelPayoutUseCase.Result result = env.cancelPayout.cancel("cancel-1", payout.id(),
                "changed my mind");

        assertThat(result.replay()).isFalse();
        Payout cancelled = result.payout();
        assertThat(cancelled.state()).isEqualTo(PayoutState.CANCELLED);
        assertThat(cancelled.failureReason()).isNull();
        var transition = env.payouts.transitionsOf(payout.id()).get(1);
        assertThat(transition.from()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(transition.to()).isEqualTo(PayoutState.CANCELLED);
        assertThat(transition.trigger()).isEqualTo("api");
        assertThat(transition.actor()).isEqualTo("principal");
        assertThat(transition.note()).isEqualTo("changed my mind");
        // full ledger reversal of the hold
        var release = env.ledger.entry("payouts:" + payout.id() + ":release").orElseThrow();
        assertThat(release.entryType()).isEqualTo(LedgerPort.EntryType.REVERSAL);
        assertThat(release.reversesEntryId()).isEqualTo(cancelled.holdEntryId());
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE);
        assertThat(env.ledger.balanceOf("payouts-clearing:KES", "KES")).isZero();
        // the scheduler wake-up was cancelled
        assertThat(env.scheduler.cancellations()).containsExactly(payout.id());
        // idempotency record kept
        assertThat(env.idempotency.contains(
                com.sharkpay.payouts.ports.IdempotencyStore.Scope.CANCEL_PAYOUT, "cancel-1"))
                .isTrue();
    }

    @Test
    void aBlankReasonDefaultsToCancelledByPrincipal() {
        Payout payout = env.createPayout("k-a");
        env.cancelPayout.cancel("k", payout.id(), null);
        assertThat(env.payouts.transitionsOf(payout.id()).get(1).note())
                .isEqualTo("cancelled by principal");

        Payout other = env.createPayout("k-b");
        env.cancelPayout.cancel("k2", other.id(), "   ");
        assertThat(env.payouts.transitionsOf(other.id()).get(1).note())
                .isEqualTo("cancelled by principal");
    }

    @Test
    void theIdempotencyKeyReplaysTheCancelledPayoutExactlyOnce() {
        Payout payout = env.createDefaultPayout();
        CancelPayoutUseCase.Result first = env.cancelPayout.cancel("cancel-1", payout.id(), null);
        int journalBefore = env.ledger.journal().size();

        CancelPayoutUseCase.Result replay = env.cancelPayout.cancel("cancel-1", payout.id(),
                "retry after timeout");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.payout().id()).isEqualTo(first.payout().id());
        assertThat(env.ledger.journal()).hasSize(journalBefore); // no second release
        assertThat(env.ledger.attemptCount("payouts:" + payout.id() + ":release")).isEqualTo(1);
        assertThat(env.scheduler.cancellations()).containsExactly(payout.id()); // once
    }

    @Test
    void theSameKeyAgainstADifferentPayoutIsA409() {
        Payout first = env.createPayout("k-1");
        env.cancelPayout.cancel("shared-key", first.id(), null);
        Payout second = env.createPayout("k-2");
        assertThatThrownBy(() -> env.cancelPayout.cancel("shared-key", second.id(), null))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(env.payouts.findById(second.id()).orElseThrow().state())
                .isEqualTo(PayoutState.PENDING_RISK); // untouched
        assertThat(env.ledger.journal()).hasSize(3); // two holds + one release
    }

    @Test
    void aBlankKeyIsRejected() {
        Payout payout = env.createDefaultPayout();
        for (String bad : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> env.cancelPayout.cancel(bad, payout.id(), null))
                    .as("key %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Idempotency-Key header must not be blank");
        }
    }

    @Test
    void cancellingAnUnknownPayoutIsA404() {
        assertThatThrownBy(() -> env.cancelPayout.cancel("k",
                "pot_0000000000000000000000000", null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void cancellingPastPendingRiskIsA409AndReleasesNothing() {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(java.time.Duration.ofSeconds(1));
        env.releaseDue.releaseDue(); // → PROCESSING

        assertThatThrownBy(() -> env.cancelPayout.cancel("k", payout.id(), null))
                .isInstanceOf(com.sharkpay.payouts.domain.PayoutStateException.class)
                .hasMessageContaining("PROCESSING");
        assertThat(env.ledger.journal()).hasSize(1); // hold only — no release
        assertThat(env.scheduler.cancellations()).isEmpty();
        assertThat(env.idempotency.contains(
                com.sharkpay.payouts.ports.IdempotencyStore.Scope.CANCEL_PAYOUT, "k"))
                .isFalse(); // nothing recorded for the refused cancel
    }

    @Test
    void aReleaseRejectionSurfacesAsALedgerPostingException() {
        Payout payout = env.createDefaultPayout();
        env.ledger.reject("payouts:" + payout.id() + ":release", "balance_invariant",
                "cannot reverse");
        assertThatThrownBy(() -> env.cancelPayout.cancel("k", payout.id(), null))
                .isInstanceOf(com.sharkpay.payouts.domain.LedgerPostingException.class)
                .hasMessageContaining("parked for ops");
    }

    @Test
    void aReplayWhoseOriginalDisappearedSurfacesLoudly() {
        Payout payout = env.createDefaultPayout();
        env.cancelPayout.cancel("lost", payout.id(), null);
        env.payouts.remove(payout.id());
        assertThatThrownBy(() -> env.cancelPayout.cancel("lost", payout.id(), null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("is missing");
    }
}
