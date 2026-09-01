package com.sharkpay.wallet.service;

import com.sharkpay.money.Money;
import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.HoldState;
import com.sharkpay.wallet.domain.HoldStateException;
import com.sharkpay.wallet.domain.IdempotencyConflictException;
import com.sharkpay.wallet.domain.InsufficientFundsException;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Capture-hold money-safety tests (ADR 003 gate G2): idempotency (same key ⇒
 * same hold, no double effect), the exact terminal split
 * {@code captured + released = amount} (partial capture releases the
 * remainder), and conflict/validation rejections.
 */
class CaptureHoldUseCaseTest {

    private final WalletTestEnv env = new WalletTestEnv();

    @Test
    void fullCaptureWhenNoAmountIsGiven() {
        Hold hold = fundedAndHeld(40_000);

        CaptureHoldUseCase.Result result = env.captureHold.capture("cap-1", hold.id(), null, null);

        assertThat(result.replay()).isFalse();
        assertThat(result.hold().state()).isEqualTo(HoldState.CAPTURED);
        assertThat(result.hold().captured()).isEqualTo(Money.of(40_000, "KES"));
        assertThat(result.hold().released()).isEqualTo(Money.zero("KES"));
    }

    @Test
    void partialCaptureSettlesTheCapturedPartAndReleasesTheRemainder() {
        Hold hold = fundedAndHeld(40_000);

        CaptureHoldUseCase.Result result = env.captureHold.capture("cap-1", hold.id(), 15_000L,
                "goods shipped");

        assertThat(result.hold().state()).isEqualTo(HoldState.CAPTURED);
        assertThat(result.hold().captured()).isEqualTo(Money.of(15_000, "KES"));
        assertThat(result.hold().released()).isEqualTo(Money.of(25_000, "KES"));
        // the exact-split invariant, asserted at the source of truth
        assertThat(result.hold().captured().add(result.hold().released()))
                .isEqualTo(result.hold().amount());

        // nothing is held anymore: the remainder is spendable again
        Wallet wallet = env.wallets.findById(hold.walletId()).orElseThrow();
        assertThat(env.balanceReader.balancesOf(wallet).held().amountMinor()).isZero();
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                .isEqualTo(100_000);
    }

    @Test
    void sameKeySamePayloadReplaysTheOriginalCaptureWithNoSecondEffect() {
        Hold hold = fundedAndHeld(40_000);
        CaptureHoldUseCase.Result first = env.captureHold.capture("cap-1", hold.id(), 15_000L, "r");
        env.events.reset();

        CaptureHoldUseCase.Result replay = env.captureHold.capture("cap-1", hold.id(), 15_000L, "r");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.hold()).isEqualTo(first.hold());
        assertThat(replay.hold().captured().amountMinor()).isEqualTo(15_000);
        assertThat(replay.hold().released().amountMinor()).isEqualTo(25_000);
        // no double effect: a second capture would throw, the split is unchanged
        assertThat(env.holds.findById(hold.id()).orElseThrow().state()).isEqualTo(HoldState.CAPTURED);
        assertThat(env.events.events()).isEmpty();
    }

    @Test
    void replayingFullAndPartialFormsOfTheSameCaptureKeyIsNotIdempotent() {
        // null (full) and an explicit amount are different canonical payloads
        Hold hold = fundedAndHeld(40_000);
        env.captureHold.capture("cap-1", hold.id(), null, null);

        assertThatThrownBy(() -> env.captureHold.capture("cap-1", hold.id(), 40_000L, null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("cap-1");
    }

    @Test
    void sameKeyDifferentPayloadIsAConflictWithNoEffect() {
        Hold hold = fundedAndHeld(40_000);
        env.captureHold.capture("cap-1", hold.id(), 15_000L, "first");

        assertThatThrownBy(() -> env.captureHold.capture("cap-1", hold.id(), 20_000L, "second"))
                .isInstanceOf(IdempotencyConflictException.class);
        // the original split is untouched
        Hold stored = env.holds.findById(hold.id()).orElseThrow();
        assertThat(stored.captured().amountMinor()).isEqualTo(15_000);
        assertThat(stored.released().amountMinor()).isEqualTo(25_000);
    }

    @Test
    void aFailedCaptureReleasesItsIdempotencyKey() {
        Hold hold = fundedAndHeld(40_000);
        // amount above the reservation fails
        assertThatThrownBy(() -> env.captureHold.capture("cap-1", hold.id(), 40_001L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(env.idempotency.find(
                com.sharkpay.wallet.ports.IdempotencyStore.Scope.CAPTURE_HOLD, "cap-1")).isEmpty();

        // the retry with the SAME key now succeeds
        CaptureHoldUseCase.Result retry = env.captureHold.capture("cap-1", hold.id(), 40_000L, null);
        assertThat(retry.replay()).isFalse();
        assertThat(retry.hold().state()).isEqualTo(HoldState.CAPTURED);
    }

    @Test
    void captureAboveTheReservedAmountIsRejected() {
        Hold hold = fundedAndHeld(40_000);

        assertThatThrownBy(() -> env.captureHold.capture("cap-1", hold.id(), 40_001L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the reserved amount");
        // rejected whole: the hold is still ACTIVE with no split
        Hold stored = env.holds.findById(hold.id()).orElseThrow();
        assertThat(stored.state()).isEqualTo(HoldState.ACTIVE);
        assertThat(stored.captured().amountMinor()).isZero();
        assertThat(stored.released().amountMinor()).isZero();
    }

    @Test
    void zeroAndNegativeCaptureAmountsAreRejected() {
        Hold hold = fundedAndHeld(40_000);
        assertThatThrownBy(() -> env.captureHold.capture("cap-1", hold.id(), 0L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> env.captureHold.capture("cap-1", hold.id(), -1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(env.holds.findById(hold.id()).orElseThrow().state()).isEqualTo(HoldState.ACTIVE);
    }

    @Test
    void capturingACapturedHoldIsAStateConflict() {
        Hold hold = fundedAndHeld(40_000);
        env.captureHold.capture("cap-1", hold.id(), 10_000L, null);

        assertThatThrownBy(() -> env.captureHold.capture("cap-2", hold.id(), 10_000L, null))
                .isInstanceOf(HoldStateException.class)
                .hasMessageContaining("capture a captured hold");
    }

    @Test
    void capturingAReleasedHoldIsAStateConflict() {
        Hold hold = fundedAndHeld(40_000);
        env.releaseHold.release("rel-1", hold.id(), null);

        assertThatThrownBy(() -> env.captureHold.capture("cap-1", hold.id(), 10_000L, null))
                .isInstanceOf(HoldStateException.class)
                .hasMessageContaining("capture a released hold");
    }

    @Test
    void frozenWalletsStillAllowCaptures() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        Hold hold = env.placeHold.place("k1", wallet.id(), 40_000, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null).hold();
        env.changeStatus.freeze(wallet.id(), "case-12");

        CaptureHoldUseCase.Result result = env.captureHold.capture("cap-1", hold.id(), 40_000L, null);

        // settling an existing commitment is not a new outflow
        assertThat(result.hold().state()).isEqualTo(HoldState.CAPTURED);
    }

    @Test
    void capturePublishesHoldCapturedAndBalanceChanged() {
        Hold hold = fundedAndHeld(40_000);
        env.events.reset();

        env.captureHold.capture("cap-1", hold.id(), 15_000L, "partial");

        assertThat(env.events.events()).hasSize(2);
        assertThat(env.events.events().get(0).type()).isEqualTo(WalletEvents.HOLD_CAPTURED);
        assertThat(env.events.events().get(1).type()).isEqualTo(WalletEvents.BALANCE_CHANGED);
        WalletEvents.HoldCapturedData data =
                (WalletEvents.HoldCapturedData) env.events.events().get(0).data();
        assertThat(data.state()).isEqualTo("captured");
        assertThat(data.amount().amount_minor()).isEqualTo(40_000);
        assertThat(data.captured_amount().amount_minor()).isEqualTo(15_000);
        assertThat(data.released_amount().amount_minor()).isEqualTo(25_000);
        WalletEvents.WalletBalanceData balance =
                (WalletEvents.WalletBalanceData) env.events.events().get(1).data();
        assertThat(balance.balances().held().amount_minor()).isZero();
        assertThat(balance.balances().available().amount_minor()).isEqualTo(100_000);
    }

    @Test
    void unknownHoldIsNotFoundAndBlankKeysAreRejected() {
        assertThatThrownBy(() -> env.captureHold.capture("cap-1",
                "hld_0123456789abcdef0123456789abcdef", 1L, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");

        Hold hold = fundedAndHeld(40_000);
        assertThatThrownBy(() -> env.captureHold.capture("  ", hold.id(), 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> env.captureHold.capture(null, hold.id(), 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> env.captureHold.capture("cap-1", " ", 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hold id");
    }

    @Test
    void aMissingReferencedHoldOnReplayIsAnError() {
        Hold hold = fundedAndHeld(40_000);
        String fingerprint = CaptureHoldUseCase.fingerprint(hold.id(), 15_000L, "r");
        env.idempotency.put(com.sharkpay.wallet.ports.IdempotencyStore.Scope.CAPTURE_HOLD,
                "ghost-key", new com.sharkpay.wallet.ports.IdempotencyStore.StoredRequest(
                        fingerprint, "hld_0123456789abcdef0123456789abcdef"));

        assertThatThrownBy(() -> env.captureHold.capture("ghost-key", hold.id(), 15_000L, "r"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("referenced by idempotency key");
    }

    @Test
    void captureFingerprintDistinguishesFullFromExplicitAmountAndReason() {
        assertThat(CaptureHoldUseCase.fingerprint("hld_1", null, null))
                .isNotEqualTo(CaptureHoldUseCase.fingerprint("hld_1", 500L, null));
        assertThat(CaptureHoldUseCase.fingerprint("hld_1", 500L, "a"))
                .isNotEqualTo(CaptureHoldUseCase.fingerprint("hld_1", 500L, "b"));
        assertThat(CaptureHoldUseCase.fingerprint("hld_1", 500L, "a"))
                .isEqualTo(CaptureHoldUseCase.fingerprint("hld_1", 500L, "a"));
    }

    private Hold fundedAndHeld(long amountMinor) {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        return env.placeHold.place("place-" + UUID.randomUUID(), wallet.id(), amountMinor,
                "KES", Source.PAYMENTS, UUID.randomUUID(), null).hold();
    }
}
