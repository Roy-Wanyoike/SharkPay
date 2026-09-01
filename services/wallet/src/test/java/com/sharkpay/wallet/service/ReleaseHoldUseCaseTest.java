package com.sharkpay.wallet.service;

import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.HoldState;
import com.sharkpay.wallet.domain.IdempotencyConflictException;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseHoldUseCaseTest {

    private final WalletTestEnv env = new WalletTestEnv();

    @Test
    void releaseReturnsTheReservedFundsToAvailable() {
        Wallet wallet = fundedWallet();
        Hold hold = placeHold(wallet, 40_000);

        ReleaseHoldUseCase.Result result = env.releaseHold.release("rel-1", hold.id(), "not needed");

        assertThat(result.replay()).isFalse();
        assertThat(result.hold().state()).isEqualTo(HoldState.RELEASED);
        assertThat(result.hold().released().amountMinor()).isEqualTo(40_000);
        assertThat(env.balanceReader.balancesOf(wallet).held().amountMinor()).isZero();
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                .isEqualTo(100_000);
        assertThat(env.balanceReader.balancesOf(wallet).total().amountMinor()).isEqualTo(100_000);
    }

    @Test
    void publishesHoldReleasedAndBalanceChanged() {
        Wallet wallet = fundedWallet();
        Hold hold = placeHold(wallet, 30_000);
        env.events.reset();

        env.releaseHold.release("rel-1", hold.id(), null);

        assertThat(env.events.events()).hasSize(2);
        assertThat(env.events.events().get(0).type()).isEqualTo(WalletEvents.HOLD_RELEASED);
        assertThat(env.events.events().get(1).type()).isEqualTo(WalletEvents.BALANCE_CHANGED);
        WalletEvents.HoldEventData data =
                (WalletEvents.HoldEventData) env.events.events().get(0).data();
        assertThat(data.state()).isEqualTo("released");
        assertThat(data.amount().amount_minor()).isEqualTo(30_000);
    }

    @Test
    void sameKeySamePayloadReplaysWithNoSecondEffect() {
        Wallet wallet = fundedWallet();
        Hold hold = placeHold(wallet, 40_000);
        ReleaseHoldUseCase.Result first = env.releaseHold.release("rel-1", hold.id(), "r");
        env.events.reset();

        ReleaseHoldUseCase.Result replay = env.releaseHold.release("rel-1", hold.id(), "r");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.hold()).isEqualTo(first.hold());
        assertThat(env.holds.count()).isEqualTo(1);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                .isEqualTo(100_000);
        assertThat(env.events.events()).isEmpty();
    }

    @Test
    void sameKeyDifferentPayloadIsAConflict() {
        Wallet wallet = fundedWallet();
        Hold hold = placeHold(wallet, 40_000);
        env.releaseHold.release("rel-1", hold.id(), "first reason");

        assertThatThrownBy(() -> env.releaseHold.release("rel-1", hold.id(), "different reason"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void releasingAReleasedHoldIsAStateConflict() {
        Wallet wallet = fundedWallet();
        Hold hold = placeHold(wallet, 40_000);
        env.releaseHold.release("rel-1", hold.id(), "r");

        assertThatThrownBy(() -> env.releaseHold.release("rel-2", hold.id(), "again"))
                .isInstanceOf(com.sharkpay.wallet.domain.HoldStateException.class)
                .hasMessageContaining("release a released hold");
    }

    @Test
    void releasingACapturedHoldIsAStateConflict() {
        Wallet wallet = fundedWallet();
        Hold hold = placeHold(wallet, 40_000);
        env.captureHold.capture("cap-1", hold.id(), 10_000L, null);

        assertThatThrownBy(() -> env.releaseHold.release("rel-1", hold.id(), "too late"))
                .isInstanceOf(com.sharkpay.wallet.domain.HoldStateException.class)
                .hasMessageContaining("release a captured hold");
    }

    @Test
    void frozenWalletsStillAllowReleases() {
        Wallet wallet = fundedWallet();
        Hold hold = placeHold(wallet, 40_000);
        env.changeStatus.freeze(wallet.id(), "case-5");

        ReleaseHoldUseCase.Result result = env.releaseHold.release("rel-1", hold.id(), "parked");

        assertThat(result.hold().state()).isEqualTo(HoldState.RELEASED);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                .isEqualTo(100_000);
    }

    @Test
    void unknownHoldIsNotFoundAndBlankKeysAreRejected() {
        assertThatThrownBy(() -> env.releaseHold.release("rel-1",
                "hld_0123456789abcdef0123456789abcdef", null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");

        Wallet wallet = fundedWallet();
        Hold hold = placeHold(wallet, 1_000);
        assertThatThrownBy(() -> env.releaseHold.release("  ", hold.id(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> env.releaseHold.release("rel-1", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hold id");
    }

    @Test
    void aFailedReleaseReleasesItsIdempotencyKey() {
        Wallet wallet = fundedWallet();
        Hold hold = placeHold(wallet, 40_000);
        env.releaseHold.release("rel-1", hold.id(), "done");
        // replaying the SAME key + payload is a successful replay, not an error
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> env.releaseHold.release("rel-1", hold.id(), "done"))
                .doesNotThrowAnyException();

        // a *different* key on the terminal hold fails and frees the key
        assertThatThrownBy(() -> env.releaseHold.release("rel-2", hold.id(), "again"))
                .isInstanceOf(com.sharkpay.wallet.domain.HoldStateException.class);
        // key never stored: store holds only the successful rel-1
        assertThat(env.idempotency.find(
                com.sharkpay.wallet.ports.IdempotencyStore.Scope.RELEASE_HOLD, "rel-2")).isEmpty();
    }

    private Wallet fundedWallet() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        return wallet;
    }

    private Hold placeHold(Wallet wallet, long amountMinor) {
        return env.placeHold.place("place-" + UUID.randomUUID(), wallet.id(), amountMinor,
                "KES", Source.PAYMENTS, UUID.randomUUID(), null).hold();
    }
}
