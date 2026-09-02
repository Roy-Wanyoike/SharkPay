package com.sharkpay.payouts.service;

import com.sharkpay.money.Money;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.ReturnCompensationException;
import com.sharkpay.payouts.events.PayoutEvents;
import com.sharkpay.payouts.ports.IdempotencyStore;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HandleProviderResultUseCase — the money heart of the payout lifecycle:
 * settle (one atomic capture entry), fail (hold released in full), and the
 * return compensation with EXACT integer arithmetic
 * ({@code re-credit = returned − non_refundable_fee}), idempotent on the
 * provider return reference (double-return rejection) and refusing any
 * uncomputable compensation BEFORE a posting is attempted.
 */
class HandleProviderResultUseCaseTest {

    private final PayoutsTestEnv env = new PayoutsTestEnv();

    @Test
    void aPendingResultWalksProcessingToSent() {
        Payout payout = submitted();

        HandleProviderResultUseCase.Result result = env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.PENDING, "rail accepted", null, null, null,
                null);

        assertThat(result.payout().state()).isEqualTo(PayoutState.SENT);
        assertThat(result.replay()).isFalse();
        assertThat(env.events.eventsOfType(PayoutEvents.SENT)).hasSize(1);
        assertThat(env.ledger.journal()).hasSize(1); // the hold only — no money moved
    }

    @Test
    void aPendingResultOnAPreSentPayoutIsAMonotonicNoOp() {
        Payout payout = env.createDefaultPayout(); // PENDING_RISK, never submitted

        HandleProviderResultUseCase.Result result = env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.PENDING, null, null, null, null, null);

        assertThat(result.payout().state()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(env.events.count()).isEqualTo(1); // only the created event
    }

    @Test
    void aSucceededResultWalksTheStateMachineForwardAndSettlesAtomically() {
        Payout payout = submitted(); // PROCESSING, no intermediate SENT signal

        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                "settled", null, null, null, null);

        Payout settled = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(settled.state()).isEqualTo(PayoutState.SUCCEEDED);
        assertThat(settled.settleEntryId()).isNotNull();
        assertThat(settled.providerRef()).isEqualTo("honeycoin:hc_000001");
        // exactly one SENT then one SUCCEEDED event — no skipped transition
        assertThat(env.events.eventsOfType(PayoutEvents.SENT)).hasSize(1);
        assertThat(env.events.eventsOfType(PayoutEvents.SUCCEEDED)).hasSize(1);
        // the capture entry: clearing → rail + fees, ONE atomic posting
        var settle = env.ledger.entry("payouts:" + payout.id() + ":settle").orElseThrow();
        assertThat(settle.entryType()).isEqualTo(LedgerPort.EntryType.CAPTURE);
        assertThat(settle.legs()).hasSize(3);
        assertThat(env.ledger.balanceOf("payouts-clearing:KES", "KES")).isZero();
        assertThat(env.ledger.balanceOf("payouts-rail:KES", "KES")).isEqualTo(500_000);
        assertThat(env.ledger.balanceOf("payouts-fees:KES", "KES")).isEqualTo(10_500);
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE - 510_500);
    }

    @Test
    void aSucceededResultOnAnAlreadySentPayoutSettlesDirectly() {
        Payout payout = sent();

        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);

        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.SUCCEEDED);
        assertThat(env.events.eventsOfType(PayoutEvents.SENT)).isEmpty(); // already SENT
        assertThat(env.events.eventsOfType(PayoutEvents.SUCCEEDED)).hasSize(1);
    }

    @Test
    void aSucceededResultOnAnUnsubmittedPayoutIsA409StateConflict() {
        Payout payout = env.createDefaultPayout(); // PENDING_RISK
        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.SUCCEEDED, null, null, null, null, null))
                .isInstanceOf(com.sharkpay.payouts.domain.PayoutStateException.class);
        assertThat(env.ledger.journal()).hasSize(1); // untouched
    }

    @Test
    void aSettlePostingRejectionParksThePayoutInSentAsA500() {
        Payout payout = sent();
        env.ledger.reject("payouts:" + payout.id() + ":settle", "balance_invariant",
                "cannot settle");

        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.SUCCEEDED, null, null, null, null, null))
                .isInstanceOf(com.sharkpay.payouts.domain.LedgerPostingException.class)
                .hasMessageContaining("parked in SENT for ops");
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.SENT); // parked, not transitioned
    }

    @Test
    void aFailedResultReleasesTheHoldInFullAndTerminatesFailed() {
        Payout payout = submitted();

        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.FAILED, null,
                "rail failure confirmed", null, null, null);

        Payout failed = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(failed.state()).isEqualTo(PayoutState.FAILED);
        assertThat(failed.failureReason()).isEqualTo("rail failure confirmed");
        var release = env.ledger.entry("payouts:" + payout.id() + ":release").orElseThrow();
        assertThat(release.entryType()).isEqualTo(LedgerPort.EntryType.REVERSAL);
        assertThat(release.reversesEntryId()).isEqualTo(failed.holdEntryId());
        // full refund: amount + fee back on the wallet, clearing empty
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE);
        assertThat(env.ledger.balanceOf("payouts-clearing:KES", "KES")).isZero();
        assertThat(env.events.eventsOfType(PayoutEvents.FAILED)).hasSize(1);
    }

    @Test
    void aFailedResultOnAnUnheldPayoutStillFailsWithoutALedgerTouch() {
        // payout that never got a hold: constructed directly in CREATED
        Payout created = payoutIn(PayoutState.CREATED);
        env.payouts.save(created);

        env.providerResults.ingest(created.id(), ProviderGatewayPort.ProviderStatus.FAILED, null,
                "early rejection", null, null, null);

        assertThat(env.payouts.findById(created.id()).orElseThrow().state())
                .isEqualTo(PayoutState.FAILED);
        assertThat(env.ledger.journal()).isEmpty(); // isHeld() false → no reversal attempted
        assertThat(env.events.eventsOfType(PayoutEvents.FAILED)).hasSize(1);
    }

    @Test
    void aReturnedResultReCreditsTheWalletMinusTheNonRefundableFeeExactly() {
        Payout payout = settled();

        HandleProviderResultUseCase.Result result = env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "msisdn_not_registered",
                500_000L, "KES", "ret-001");

        assertThat(result.payout().state()).isEqualTo(PayoutState.RETURNED);
        Payout returned = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(returned.returnReason()).isEqualTo("msisdn_not_registered");
        assertThat(returned.returnEntryId()).isNotNull();
        // ONE compensation entry: reversal of the settle entry
        var compensation = env.ledger.entry("payouts:" + payout.id() + ":return").orElseThrow();
        assertThat(compensation.entryType()).isEqualTo(LedgerPort.EntryType.REVERSAL);
        assertThat(compensation.reversesEntryId()).isEqualTo(payout.settleEntryId());
        assertThat(compensation.legs()).hasSize(3);
        // G2 exactness: re-credit = 500000 − 5500
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE - 510_500 + 494_500);
        assertThat(env.ledger.balanceOf("payouts-rail:KES", "KES")).isZero();
        assertThat(env.ledger.balanceOf("payouts-fees:KES", "KES"))
                .isEqualTo(10_500 + 5_500); // fee + retained non-refundable
        assertThat(env.events.eventsOfType(PayoutEvents.RETURNED)).hasSize(1);
        assertThat(env.ledger.journal()).hasSize(3); // hold + settle + return — no more
    }

    @Test
    void theReturnAmountDefaultsToTheFullPayoutAmount() {
        Payout payout = settled();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.RETURNED,
                null, null, null, null, "ret-full");
        assertThat(env.ledger.legsOf("payouts:" + payout.id() + ":return").get(0).amount())
                .isEqualTo(Money.of(500_000, "KES"));
        assertThat(env.payouts.findById(payout.id()).orElseThrow().returnReason())
                .isEqualTo("returned by rail");
    }

    @Test
    void aPartialReturnCompensatesThePartialAmount() {
        Payout payout = settled();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.RETURNED,
                null, "partial", 200_000L, "KES", "ret-part");
        // re-credit = 200000 − 5500
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE - 510_500 + 194_500);
        assertThat(env.ledger.balanceOf("payouts-rail:KES", "KES")).isEqualTo(300_000);
    }

    @Test
    void aReturnEqualToTheNonRefundableFeeReCreditsNothingAndRetainsTheFee() {
        Payout payout = settled();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.RETURNED,
                null, "fee-only return", 5_500L, "KES", "ret-fee");

        Payout returned = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(returned.state()).isEqualTo(PayoutState.RETURNED);
        // 2-leg compensation: rail debit 5500, fees credit 5500 — no zero wallet leg
        var legs = env.ledger.legsOf("payouts:" + payout.id() + ":return");
        assertThat(legs).hasSize(2);
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE - 510_500); // nothing re-credited
        assertThat(env.ledger.balanceOf("payouts-fees:KES", "KES"))
                .isEqualTo(10_500 + 5_500);
    }

    @Test
    void theSameReturnReferenceReplaysWithoutASecondCompensation() {
        Payout payout = settled();
        HandleProviderResultUseCase.Result first = env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "first", 500_000L, "KES",
                "ret-dedupe");
        int journalBefore = env.ledger.journal().size();
        int eventsBefore = env.events.count();

        HandleProviderResultUseCase.Result replay = env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "second", 500_000L, "KES",
                "ret-dedupe");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.payout().id()).isEqualTo(first.payout().id());
        assertThat(env.ledger.journal()).hasSize(journalBefore); // exactly one compensation
        assertThat(env.events.count()).isEqualTo(eventsBefore);
        assertThat(env.ledger.effectCount("payouts:" + payout.id() + ":return")).isEqualTo(1);
    }

    @Test
    void theSameReturnReferenceWithADifferentStatusIsA409() {
        Payout payout = settled();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.RETURNED,
                null, "r", 500_000L, "KES", "ret-key");
        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.SUCCEEDED, null, null, null, null, "ret-key"))
                .isInstanceOf(com.sharkpay.payouts.domain.IdempotencyConflictException.class);
    }

    @Test
    void aSecondDifferentReturnOnATerminalPayoutIsRejected() {
        Payout payout = settled();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.RETURNED,
                null, "first", 500_000L, "KES", "ret-1");
        int journalBefore = env.ledger.journal().size();

        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "again", 500_000L, "KES",
                "ret-2"))
                .isInstanceOf(ReturnCompensationException.class)
                .hasFieldOrPropertyWithValue("reason",
                        ReturnCompensationException.Reason.NOT_RETURNABLE);
        assertThat(env.ledger.journal()).hasSize(journalBefore); // no second posting
    }

    @Test
    void aReturnOnANonReturnablePayoutIsRejectedBeforeAnyPosting() {
        Payout submitted = submitted(); // PROCESSING
        assertThatThrownBy(() -> env.providerResults.ingest(submitted.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "r", 500_000L, "KES", null))
                .isInstanceOf(ReturnCompensationException.class)
                .hasFieldOrPropertyWithValue("reason",
                        ReturnCompensationException.Reason.NOT_RETURNABLE)
                .hasMessageContaining("PROCESSING");
        assertThat(env.ledger.journal()).hasSize(1); // hold only — untouched
    }

    @Test
    void aReturnOverThePayoutAmountIsRejectedAsAnOpsCase() {
        Payout payout = settled();
        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "r", 500_001L, "KES", null))
                .isInstanceOf(ReturnCompensationException.class)
                .hasMessageContaining("exceeds the payout amount");
        assertThat(env.ledger.journal()).hasSize(2); // hold + settle only
    }

    @Test
    void aReturnBelowTheNonRefundableFeeIsRejectedWithNoPosting() {
        Payout payout = settled();
        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "r", 5_499L, "KES", null))
                .isInstanceOf(ReturnCompensationException.class)
                .hasFieldOrPropertyWithValue("reason",
                        ReturnCompensationException.Reason.NEGATIVE_COMPENSATION)
                .hasMessageContaining("ops case required");
        assertThat(env.ledger.journal()).hasSize(2); // hold + settle only — nothing moved
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.SUCCEEDED); // stays where it was
    }

    @Test
    void aReturnCurrencyMismatchIsRejectedWithNoPosting() {
        Payout payout = settled();
        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "r", 500_000L, "USD", null))
                .isInstanceOf(ReturnCompensationException.class)
                .hasFieldOrPropertyWithValue("reason",
                        ReturnCompensationException.Reason.CURRENCY_MISMATCH);
        assertThat(env.ledger.journal()).hasSize(2);
    }

    @Test
    void aReturnCompensationRejectionParksForOpsWithNoReCredit() {
        Payout payout = settled();
        env.ledger.reject("payouts:" + payout.id() + ":return", "balance_invariant",
                "cannot compensate");
        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "r", 500_000L, "KES", null))
                .isInstanceOf(com.sharkpay.payouts.domain.LedgerPostingException.class)
                .hasMessageContaining("no re-credit");
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.SUCCEEDED); // parked, not RETURNED
    }

    @Test
    void anUnknownStatusParksThePayoutWithNoTransitionAndNoRetry() {
        Payout payout = submitted();
        int eventsBefore = env.events.count();
        int initiationsBefore = env.gateway.initiateAttemptsFor(payout.id());

        HandleProviderResultUseCase.Result result = env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.UNKNOWN, null, null, null, null, null);

        assertThat(result.payout().state()).isEqualTo(PayoutState.PROCESSING); // parked
        assertThat(env.events.count()).isEqualTo(eventsBefore); // no event
        assertThat(env.ledger.journal()).hasSize(1); // no money touched
        // ambiguity never re-submits the debit — the one initiation is the fixture's
        assertThat(env.gateway.initiateAttemptsFor(payout.id())).isEqualTo(initiationsBefore);
    }

    @Test
    void anUnknownPayoutIdIsA404() {
        assertThatThrownBy(() -> env.providerResults.ingest("pot_0000000000000000000000000",
                ProviderGatewayPort.ProviderStatus.SUCCEEDED, null, null, null, null, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void theIngestArgumentsAreNullChecked() {
        Payout payout = submitted();
        assertThatThrownBy(() -> env.providerResults.ingest(null,
                ProviderGatewayPort.ProviderStatus.SUCCEEDED, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payoutId is required");
        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(), null, null, null, null,
                null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status is required");
    }

    @Test
    void aReplayWhoseOriginalDisappearedSurfacesLoudly() {
        Payout payout = settled();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.RETURNED,
                null, "r", 500_000L, "KES", "ret-lost");
        env.payouts.remove(payout.id());

        assertThatThrownBy(() -> env.providerResults.ingest(payout.id(),
                ProviderGatewayPort.ProviderStatus.RETURNED, null, "r", 500_000L, "KES",
                "ret-lost"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void theIdempotencyRecordIsOnlyKeptForReferencedReturns() {
        Payout payout = submitted();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.PENDING,
                null, null, null, null, null);
        assertThat(env.idempotency.count(
                com.sharkpay.payouts.ports.IdempotencyStore.Scope.PROVIDER_RESULT)).isZero();

        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, "ret-77");
        assertThat(env.idempotency.contains(IdempotencyStore.Scope.PROVIDER_RESULT, "ret-77"))
                .isTrue();
    }

    // ── lifecycle helpers ──────────────────────────────────────────────────

    /** An accepted payout pushed to PROCESSING through one release tick. */
    private Payout submitted() {
        Payout payout = env.createDefaultPayout();
        env.clock.advance(java.time.Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        Payout loaded = env.payouts.findById(payout.id()).orElseThrow();
        assertThat(loaded.state()).as("fixture must be PROCESSING").isEqualTo(
                PayoutState.PROCESSING);
        env.events.reset();
        return loaded;
    }

    /** A payout pushed through PROCESSING → SENT. */
    private Payout sent() {
        Payout payout = submitted();
        env.providerResults.apply(payout, ProviderGatewayPort.ProviderStatus.PENDING, null,
                null, null);
        env.events.reset();
        return env.payouts.findById(payout.id()).orElseThrow();
    }

    /** A payout settled at the destination. */
    private Payout settled() {
        Payout payout = submitted();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);
        env.events.reset();
        return env.payouts.findById(payout.id()).orElseThrow();
    }

    /** A payout assembled directly in {@code state} without any ledger side effects. */
    private Payout payoutIn(PayoutState state) {
        return new Payout("pot_0123456789abcdef0123456789abcdef", java.util.UUID.randomUUID(),
                PayoutsTestEnv.WALLET, env.walletAccount, Money.of(500_000, "KES"),
                Money.of(10_500, "KES"), Money.of(5_500, "KES"),
                com.sharkpay.payouts.domain.Rail.MPESA, PayoutsTestEnv.mpesaDestination(), state,
                null, null, null, 0, null, null, PayoutsTestEnv.START.plusSeconds(900), null,
                null, null, Map.of(), PayoutsTestEnv.START, PayoutsTestEnv.START,
                java.util.List.of());
    }
}
