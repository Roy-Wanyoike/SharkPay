package com.sharkpay.payouts.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payout aggregate per docs/STATE-MACHINES.md §2 (normative):
 * CREATED → PENDING_RISK → PROCESSING → SENT → SUCCEEDED with BLOCKED,
 * FAILED, RETURNED and CANCELLED reachable exactly as documented — every
 * other transition is a bug (PayoutStateException). Also pins the retry
 * bookkeeping (attempts + nextAttemptAt without audit rows), the release
 * and expiry windows, and the hold/settle/return entry-id alignment rules.
 */
class PayoutTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final String ID = "pot_0123456789abcdef0123456789abcdef";
    private static final String WALLET = "wal_0123456789abcdef0123456789abcdef";

    @Test
    void newPayoutIsCreatedWithZeroAttemptsAndNoSideEffects() {
        Payout payout = payout();

        assertThat(payout.state()).isEqualTo(PayoutState.CREATED);
        assertThat(payout.attempts()).isZero();
        assertThat(payout.holdEntryId()).isNull();
        assertThat(payout.settleEntryId()).isNull();
        assertThat(payout.returnEntryId()).isNull();
        assertThat(payout.providerRef()).isNull();
        assertThat(payout.nextAttemptAt()).isNull();
        assertThat(payout.executeAfter()).isEqualTo(T0.plusSeconds(30));
        assertThat(payout.expiresAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(payout.metadata()).containsEntry("invoice", "INV-991");
        assertThat(payout.transitions()).isEmpty();
        assertThat(payout.isTerminal()).isFalse();
        assertThat(payout.isHeld()).isFalse();
    }

    @Test
    void acceptMovesCreatedToPendingRiskAndStampsTheHoldEntry() {
        Payout payout = payout();
        UUID holdEntry = UUID.randomUUID();

        payout.accept(T0.plusSeconds(30), holdEntry, T0);

        assertThat(payout.state()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(payout.holdEntryId()).isEqualTo(holdEntry);
        assertThat(payout.executeAfter()).isEqualTo(T0.plusSeconds(30));
        assertThat(payout.isHeld()).isTrue();
        assertThat(payout.transitions()).hasSize(1);
        StateTransition transition = payout.transitions().get(0);
        assertThat(transition.from()).isEqualTo(PayoutState.CREATED);
        assertThat(transition.to()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(transition.trigger()).isEqualTo("risk_pass");
        assertThat(transition.note()).contains("505500 KES"); // amount + fee held
    }

    @Test
    void acceptIsOnlyLegalFromCreated() {
        Payout accepted = accepted();
        assertThatThrownBy(() -> accepted.accept(T0, UUID.randomUUID(), T0))
                .isInstanceOf(PayoutStateException.class)
                .hasMessageContaining(ID)
                .hasMessageContaining("PENDING_RISK")
                .hasMessageContaining("CREATED");
    }

    @Test
    void riskDenyBlocksFromCreatedAndPendingRiskOnly() {
        Payout fromCreated = payout();
        fromCreated.riskDeny("velocity", T0);
        assertThat(fromCreated.state()).isEqualTo(PayoutState.BLOCKED);
        assertThat(fromCreated.isTerminal()).isTrue();
        assertThat(fromCreated.transitions().get(0).from()).isEqualTo(PayoutState.CREATED);
        assertThat(fromCreated.transitions().get(0).actor()).isEqualTo("risk");

        Payout fromPendingRisk = accepted();
        fromPendingRisk.riskDeny("sanctions", T0);
        assertThat(fromPendingRisk.state()).isEqualTo(PayoutState.BLOCKED);
        assertThat(fromPendingRisk.transitions().get(1).from()).isEqualTo(PayoutState.PENDING_RISK);

        for (Payout illegal : new Payout[]{processing(), sent(), succeededPayout(),
                blocked(), cancelledByUser()}) {
            assertThatThrownBy(() -> illegal.riskDeny("late", T0))
                    .as("riskDeny from %s", illegal.state())
                    .isInstanceOf(PayoutStateException.class);
        }
    }

    @Test
    void markSubmittedMovesPendingRiskToProcessingWithTheProviderRef() {
        Payout payout = accepted();
        payout.markSubmitted("honeycoin:hc_42", T0);

        assertThat(payout.state()).isEqualTo(PayoutState.PROCESSING);
        assertThat(payout.providerRef()).isEqualTo("honeycoin:hc_42");
        assertThat(payout.nextAttemptAt()).isNull();
        assertThat(payout.transitions()).hasSize(2);
        assertThat(payout.transitions().get(1).from()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(payout.transitions().get(1).to()).isEqualTo(PayoutState.PROCESSING);
        assertThat(payout.transitions().get(1).actor()).isEqualTo("scheduler");
    }

    @Test
    void markSubmittedIsOnlyLegalFromPendingRiskAndRequiresARef() {
        assertThatThrownBy(() -> accepted().markSubmitted(null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider reference must not be blank");
        assertThatThrownBy(() -> accepted().markSubmitted("   ", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accepted().markSubmitted("x".repeat(129), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 128");
        assertThatThrownBy(() -> processing().markSubmitted("honeycoin:hc_1", T0))
                .isInstanceOf(PayoutStateException.class);
    }

    @Test
    void recordSubmitFailureIsRetryBookkeepingNotATransition() {
        Payout payout = accepted();
        int transitionsBefore = payout.transitions().size();

        payout.recordSubmitFailure(T0.plusSeconds(60), T0.plusSeconds(1));

        assertThat(payout.state()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(payout.attempts()).isEqualTo(1);
        assertThat(payout.nextAttemptAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(payout.updatedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(payout.transitions()).hasSize(transitionsBefore); // no audit row
    }

    @Test
    void recordSubmitFailureIsOnlyLegalWhilePendingRisk() {
        assertThatThrownBy(() -> processing().recordSubmitFailure(T0, T0))
                .isInstanceOf(PayoutStateException.class);
        assertThatThrownBy(() -> accepted().recordSubmitFailure(null, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void markSentMovesProcessingToSent() {
        Payout payout = processing();
        payout.markSent(T0);
        assertThat(payout.state()).isEqualTo(PayoutState.SENT);
        assertThat(payout.transitions()).hasSize(3);
        assertThat(payout.transitions().get(2).to()).isEqualTo(PayoutState.SENT);
        assertThat(payout.transitions().get(2).trigger()).isEqualTo("provider_callback");
    }

    @Test
    void markSentIsOnlyLegalFromProcessing() {
        assertThatThrownBy(() -> accepted().markSent(T0))
                .isInstanceOf(PayoutStateException.class)
                .hasMessageContaining("PENDING_RISK");
        Payout sent = sent();
        assertThatThrownBy(() -> sent.markSent(T0)).isInstanceOf(PayoutStateException.class);
    }

    @Test
    void markSucceededMovesSentToSucceededAndStampsTheSettleEntry() {
        Payout payout = sent();
        UUID settleEntry = UUID.randomUUID();

        payout.markSucceeded(settleEntry, T0);

        assertThat(payout.state()).isEqualTo(PayoutState.SUCCEEDED);
        assertThat(payout.settleEntryId()).isEqualTo(settleEntry);
        assertThat(payout.isTerminal()).isTrue();
        assertThat(payout.transitions()).hasSize(4);
        assertThat(payout.transitions().get(3).to()).isEqualTo(PayoutState.SUCCEEDED);
    }

    @Test
    void markSucceededIsOnlyLegalFromSentAndRequiresTheEntry() {
        assertThatThrownBy(() -> sent().markSucceeded(null, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settleEntryId is required");
        assertThatThrownBy(() -> processing().markSucceeded(UUID.randomUUID(), T0))
                .isInstanceOf(PayoutStateException.class)
                .hasMessageContaining("PROCESSING");
        assertThatThrownBy(() -> succeededPayout().markSucceeded(UUID.randomUUID(), T0))
                .isInstanceOf(PayoutStateException.class);
    }

    @Test
    void markFailedTerminatesFromCreatedPendingRiskAndProcessingOnly() {
        for (Payout legal : new Payout[]{payout(), accepted(), processing()}) {
            legal.markFailed("rail failure", T0);
            assertThat(legal.state()).isEqualTo(PayoutState.FAILED);
            assertThat(legal.failureReason()).isEqualTo("rail failure");
            assertThat(legal.isTerminal()).isTrue();
        }
        for (Payout illegal : new Payout[]{sent(), succeededPayout(), returnedPayout(),
                blocked(), cancelledByUser()}) {
            assertThatThrownBy(() -> illegal.markFailed("late", T0))
                    .as("markFailed from %s", illegal.state())
                    .isInstanceOf(PayoutStateException.class);
        }
    }

    @Test
    void markReturnedTerminatesFromSentAndSucceededAndStampsTheCompensationEntry() {
        Payout fromSent = sent();
        fromSent.markReturned("msisdn_not_registered", UUID.randomUUID(), T0);
        assertThat(fromSent.state()).isEqualTo(PayoutState.RETURNED);
        assertThat(fromSent.returnReason()).isEqualTo("msisdn_not_registered");
        assertThat(fromSent.isTerminal()).isTrue();

        Payout fromSucceeded = succeededPayout();
        fromSucceeded.markReturned("late return", UUID.randomUUID(), T0);
        assertThat(fromSucceeded.state()).isEqualTo(PayoutState.RETURNED);

        for (Payout illegal : new Payout[]{payout(), accepted(), processing(), blocked(),
                cancelledByUser()}) {
            assertThatThrownBy(() -> illegal.markReturned("r", UUID.randomUUID(), T0))
                    .as("markReturned from %s", illegal.state())
                    .isInstanceOf(PayoutStateException.class);
        }
        // a second return on a terminal RETURNED payout is rejected (double-return)
        assertThatThrownBy(() -> fromSent.markReturned("again", UUID.randomUUID(), T0))
                .isInstanceOf(PayoutStateException.class);
        assertThatThrownBy(() -> sent().markReturned("r", null, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("returnEntryId is required");
    }

    @Test
    void userCancellationIsLegalFromCreatedAndPendingRiskOnly() {
        Payout fromCreated = payout();
        fromCreated.cancel("changed my mind", T0, false);
        assertThat(fromCreated.state()).isEqualTo(PayoutState.CANCELLED);
        assertThat(fromCreated.transitions().get(0).actor()).isEqualTo("principal");
        assertThat(fromCreated.transitions().get(0).trigger()).isEqualTo("api");

        Payout fromPendingRisk = accepted();
        fromPendingRisk.cancel("user cancel", T0, false);
        assertThat(fromPendingRisk.state()).isEqualTo(PayoutState.CANCELLED);

        for (Payout illegal : new Payout[]{processing(), sent(), succeededPayout()}) {
            assertThatThrownBy(() -> illegal.cancel("late", T0, false))
                    .as("user cancel from %s", illegal.state())
                    .isInstanceOf(PayoutStateException.class);
        }
    }

    @Test
    void systemCancellationIsAlsoLegalFromProcessingForTheTtlSweeper() {
        Payout processing = processing();
        processing.cancel("ttl expired before provider acceptance", T0, true);
        assertThat(processing.state()).isEqualTo(PayoutState.CANCELLED);
        assertThat(processing.transitions().get(2).actor()).isEqualTo("system");
        assertThat(processing.transitions().get(2).trigger()).isEqualTo("expiry");

        // system cannot cancel past PROCESSING either (never force-cancel sent money)
        assertThatThrownBy(() -> sent().cancel("ttl", T0, true))
                .isInstanceOf(PayoutStateException.class);
    }

    @Test
    void cancelRequiresANonBlankBoundedReason() {
        assertThatThrownBy(() -> payout().cancel(null, T0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason must not be blank");
        assertThatThrownBy(() -> payout().cancel("  ", T0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payout().cancel("x".repeat(513), T0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 512");
    }

    @Test
    void dueForReleaseRequiresPendingRiskAndPassedExecuteAfterAndBackoffWindows() {
        Payout payout = accepted(); // executeAfter = T0+30
        assertThat(payout.dueForRelease(T0)).isFalse();
        assertThat(payout.dueForRelease(T0.plusSeconds(29))).isFalse();
        assertThat(payout.dueForRelease(T0.plusSeconds(30))).isTrue();
        assertThat(payout.dueForRelease(T0.plusSeconds(31))).isTrue();

        payout.recordSubmitFailure(T0.plusSeconds(120), T0.plusSeconds(1));
        assertThat(payout.dueForRelease(T0.plusSeconds(100))).isFalse(); // backoff not passed
        assertThat(payout.dueForRelease(T0.plusSeconds(120))).isTrue();

        assertThat(processing().dueForRelease(T0)).isFalse(); // not PENDING_RISK
    }

    @Test
    void aNullExecuteAfterNeverBlocksRelease() {
        Payout payout = new Payout(ID, UUID.randomUUID(), WALLET, UUID.randomUUID(),
                Money.of(1_000, "KES"), Money.of(50, "KES"), Money.of(50, "KES"), Rail.MPESA,
                mpesaDestination(), PayoutState.PENDING_RISK, null, null, null, 0, null, null,
                T0.plusSeconds(900), UUID.randomUUID(), null, null, null, T0, T0, List.of());
        assertThat(payout.dueForRelease(T0)).isTrue();
    }

    @Test
    void expiredMatchesPendingRiskAndProcessingPastExpiresAt() {
        Payout payout = accepted(); // expiresAt = T0+900
        assertThat(payout.expired(T0.plusSeconds(899))).isFalse();
        assertThat(payout.expired(T0.plusSeconds(900))).isFalse(); // strictly before
        assertThat(payout.expired(T0.plusSeconds(901))).isTrue();
        assertThat(succeededPayout().expired(T0.plusSeconds(10_000))).isFalse();
    }

    @Test
    void isHeldRequiresTheHoldEntryAndLeavingCreated() {
        assertThat(payout().isHeld()).isFalse();
        assertThat(accepted().isHeld()).isTrue();
        assertThat(processing().isHeld()).isTrue();
    }

    @Test
    void pendingTransitionsDrainExactlyOnceForRepositoryPersistence() {
        Payout payout = accepted();
        payout.markSubmitted("honeycoin:hc_1", T0);
        assertThat(payout.pendingTransitions()).hasSize(2);
        payout.markTransitionsPersisted();
        assertThat(payout.pendingTransitions()).isEmpty();
        assertThat(payout.transitions()).hasSize(2);
    }

    @Test
    void theRehydrationConstructorRestoresEverythingIncludingAttemptsAndEntryIds() {
        UUID hold = UUID.randomUUID();
        UUID settle = UUID.randomUUID();
        UUID ret = UUID.randomUUID();
        StateTransition history = new StateTransition(PayoutState.SENT, PayoutState.RETURNED,
                "provider_callback", "provider", "msisdn_not_registered", T0);
        Payout rehydrated = new Payout(ID, UUID.randomUUID(), WALLET, UUID.randomUUID(),
                Money.of(500_000, "KES"), Money.of(10_500, "KES"), Money.of(5_500, "KES"),
                Rail.MPESA, mpesaDestination(), PayoutState.RETURNED, "honeycoin:hc_9",
                null, "msisdn_not_registered", 3, T0, T0.plusSeconds(60), T0.plusSeconds(900),
                hold, settle, ret, Map.of("k", "v"), T0, T0, List.of(history));

        assertThat(rehydrated.state()).isEqualTo(PayoutState.RETURNED);
        assertThat(rehydrated.attempts()).isEqualTo(3);
        assertThat(rehydrated.nextAttemptAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(rehydrated.holdEntryId()).isEqualTo(hold);
        assertThat(rehydrated.settleEntryId()).isEqualTo(settle);
        assertThat(rehydrated.returnEntryId()).isEqualTo(ret);
        assertThat(rehydrated.returnReason()).isEqualTo("msisdn_not_registered");
        assertThat(rehydrated.providerRef()).isEqualTo("honeycoin:hc_9");
        assertThat(rehydrated.transitions()).containsExactly(history);
    }

    @Test
    void negativeAttemptsAreClampedToZero() {
        Payout payout = new Payout(ID, UUID.randomUUID(), WALLET, UUID.randomUUID(),
                Money.of(1_000, "KES"), Money.of(50, "KES"), Money.of(50, "KES"), Rail.MPESA,
                mpesaDestination(), PayoutState.PENDING_RISK, null, null, null, -5, null, null,
                T0.plusSeconds(900), UUID.randomUUID(), null, null, null, T0, T0, List.of());
        assertThat(payout.attempts()).isZero();
    }

    // ── construction guards ────────────────────────────────────────────────

    @Test
    void theIdAndWalletMustMatchTheirPatterns() {
        UUID ref = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> new Payout(null, ref, WALLET, account, Money.of(1, "KES"),
                Money.zero("KES"), Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payout id must match");
        assertThatThrownBy(() -> new Payout("pot_short", ref, WALLET, account, Money.of(1, "KES"),
                Money.zero("KES"), Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Payout(ID, ref, "bad-wallet", account, Money.of(1, "KES"),
                Money.zero("KES"), Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source wallet must match");
    }

    @Test
    void theAmountMustBePositiveAndTheFeesNonNegativeSameCurrencyAndBounded() {
        UUID ref = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> payoutWith(Money.zero("KES"), Money.zero("KES"),
                Money.zero("KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payout amount must be positive");
        assertThatThrownBy(() -> payoutWith(Money.of(-1, "KES"), Money.zero("KES"),
                Money.zero("KES")))
                .isInstanceOf(IllegalArgumentException.class);
        // fee currency must match the amount
        assertThatThrownBy(() -> payoutWith(Money.of(1, "KES"), Money.zero("USD"),
                Money.zero("KES")))
                .isInstanceOf(CurrencyMismatchException.class);
        // non-refundable currency must match the amount
        assertThatThrownBy(() -> payoutWith(Money.of(1, "KES"), Money.zero("KES"),
                Money.zero("USD")))
                .isInstanceOf(CurrencyMismatchException.class);
        // non-refundable cannot exceed the total fee
        assertThatThrownBy(() -> payoutWith(Money.of(1_000, "KES"), Money.of(50, "KES"),
                Money.of(51, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-refundable fee must be between 0 and the total fee");
        // the upper bound is legal
        assertThat(payoutWith(Money.of(1_000, "KES"), Money.of(50, "KES"),
                Money.of(50, "KES")).nonRefundableFee()).isEqualTo(Money.of(50, "KES"));
    }

    @Test
    void theRailMustSupportTheDestinationAndTheCurrency() {
        UUID ref = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        // mpesa destination on the bank rail: unsupported combination
        assertThatThrownBy(() -> new Payout(ID, ref, WALLET, account, Money.of(1, "KES"),
                Money.zero("KES"), Money.zero("KES"), Rail.BANK, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("not compatible with rail bank");
        // M-Pesa is the Kenyan rail: KES only
        assertThatThrownBy(() -> new Payout(ID, ref, WALLET, account, Money.of(1, "USD"),
                Money.zero("USD"), Money.zero("USD"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(UnsupportedDestinationException.class)
                .hasMessageContaining("does not support currency USD");
        // on-chain serves the stablecoin set only
        assertThatThrownBy(() -> new Payout(ID, ref, WALLET, account, Money.of(1, "KES"),
                Money.zero("KES"), Money.zero("KES"), Rail.ON_CHAIN, onChainDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(UnsupportedDestinationException.class);
    }

    @Test
    void requiredConstructorArgumentsAreNullChecked() {
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> new Payout(ID, null, WALLET, account, Money.of(1, "KES"),
                Money.zero("KES"), Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("internalRef is required");
        // the legacy (no ledger account) constructors delegate and still demand it
        assertThatThrownBy(() -> new Payout(ID, UUID.randomUUID(), WALLET, Money.of(1, "KES"),
                Money.zero("KES"), Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("walletLedgerAccountId is required");
        assertThatThrownBy(() -> new Payout(ID, UUID.randomUUID(), WALLET, account, Money.of(1,
                "KES"), null, Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fee is required");
        assertThatThrownBy(() -> new Payout(ID, UUID.randomUUID(), WALLET, account, Money.of(1,
                "KES"), Money.zero("KES"), null, Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nonRefundableFee is required");
        assertThatThrownBy(() -> new Payout(ID, UUID.randomUUID(), WALLET, account, Money.of(1,
                "KES"), Money.zero("KES"), Money.zero("KES"), null, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rail is required");
        assertThatThrownBy(() -> new Payout(ID, UUID.randomUUID(), WALLET, account, Money.of(1,
                "KES"), Money.zero("KES"), Money.zero("KES"), Rail.MPESA, null,
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("destination is required");
        assertThatThrownBy(() -> new Payout(ID, UUID.randomUUID(), WALLET, account, Money.of(1,
                "KES"), Money.zero("KES"), Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                null, null, null, null, 0, null, null, T0, null, null, null, null, T0, T0,
                List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state is required");
        assertThatThrownBy(() -> new Payout(ID, UUID.randomUUID(), WALLET, account, Money.of(1,
                "KES"), Money.zero("KES"), Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, null, null, null, null, null,
                T0, T0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("expiresAt is required");
        assertThatThrownBy(() -> new Payout(ID, UUID.randomUUID(), WALLET, account, Money.of(1,
                "KES"), Money.zero("KES"), Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                null, T0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdAt is required");
        assertThatThrownBy(() -> new Payout(ID, UUID.randomUUID(), WALLET, account, Money.of(1,
                "KES"), Money.zero("KES"), Money.zero("KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0, null, null, null, null,
                T0, null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("updatedAt is required");
    }

    @Test
    void theLegacyConstructorsWithoutTheLedgerAccountAreUnusable() {
        // the wallet ledger account is mandatory (legs key on it) — the two
        // legacy overloads cannot satisfy the invariant and fail loudly
        // instead of silently mapping legs onto a null account
        UUID ref = UUID.randomUUID();
        assertThatThrownBy(() -> new Payout(ID, ref, WALLET, Money.of(1_000, "KES"),
                Money.of(50, "KES"), Money.of(50, "KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0.plusSeconds(900), null,
                null, null, null, T0, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("walletLedgerAccountId is required");
        assertThatThrownBy(() -> new Payout(ID, ref, WALLET, Money.of(1_000, "KES"),
                Money.of(50, "KES"), Money.of(50, "KES"), Rail.MPESA, mpesaDestination(),
                PayoutState.CREATED, null, null, null, 0, null, null, T0.plusSeconds(900), null,
                null, null, null, T0, T0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("walletLedgerAccountId is required");
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static Destination mpesaDestination() {
        return new Destination("mpesa", "+254712345678", null, null, null, null, null, null);
    }

    private static Destination onChainDestination() {
        return new Destination("on_chain", null, null, null, null, null, "base",
                "0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d");
    }

    private static Payout payout() {
        return Payout.newPayout(ID, UUID.randomUUID(), WALLET, UUID.randomUUID(),
                Money.of(500_000, "KES"), Money.of(5_500, "KES"), Money.of(5_500, "KES"),
                Rail.MPESA, mpesaDestination(), Map.of("invoice", "INV-991"), T0.plusSeconds(30),
                T0.plusSeconds(900), T0);
    }

    private static Payout payoutWith(Money amount, Money fee, Money nonRefundable) {
        return new Payout(ID, UUID.randomUUID(), WALLET, UUID.randomUUID(), amount, fee,
                nonRefundable, Rail.MPESA, mpesaDestination(), PayoutState.CREATED, null, null,
                null, 0, null, null, T0.plusSeconds(900), null, null, null, null, T0, T0, List.of());
    }

    private static Payout accepted() {
        Payout payout = payout();
        payout.accept(T0.plusSeconds(30), UUID.randomUUID(), T0);
        return payout;
    }

    private static Payout processing() {
        Payout payout = accepted();
        payout.markSubmitted("honeycoin:hc_1", T0);
        return payout;
    }

    private static Payout sent() {
        Payout payout = processing();
        payout.markSent(T0);
        return payout;
    }

    private static Payout succeededPayout() {
        Payout payout = sent();
        payout.markSucceeded(UUID.randomUUID(), T0);
        return payout;
    }

    private static Payout returnedPayout() {
        Payout payout = sent();
        payout.markReturned("returned", UUID.randomUUID(), T0);
        return payout;
    }

    private static Payout blocked() {
        Payout payout = payout();
        payout.riskDeny("blocked", T0);
        return payout;
    }

    private static Payout cancelledByUser() {
        Payout payout = payout();
        payout.cancel("cancelled", T0, false);
        return payout;
    }
}
