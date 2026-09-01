package com.sharkpay.wallet.service;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.money.UnknownCurrencyException;
import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.HoldState;
import com.sharkpay.wallet.domain.IdempotencyConflictException;
import com.sharkpay.wallet.domain.InsufficientFundsException;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.domain.WalletStateException;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The funds-control core: money-safety tests for place-hold — idempotency,
 * the non-negative available-balance invariant, currency-mismatch
 * rejection and overflow rejection (ADR 003 gate G2).
 */
class PlaceHoldUseCaseTest {

    private final WalletTestEnv env = new WalletTestEnv();

    @Test
    void placesAHoldReducingAvailableButNeverTheTotal() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        UUID sourceRef = UUID.randomUUID();

        PlaceHoldUseCase.Result result = env.placeHold.place("key-1", wallet.id(), 40_000,
                "KES", Source.PAYMENTS, sourceRef, "risk cleared");

        assertThat(result.replay()).isFalse();
        Hold hold = result.hold();
        assertThat(hold.id()).matches("^hld_[0-9a-f]{32}$");
        assertThat(hold.state()).isEqualTo(HoldState.ACTIVE);
        assertThat(hold.amount()).isEqualTo(com.sharkpay.money.Money.of(40_000, "KES"));
        // total (ledger projection) untouched; held grew; available shrank
        assertThat(env.balanceReader.balancesOf(wallet).total())
                .isEqualTo(com.sharkpay.money.Money.of(100_000, "KES"));
        assertThat(env.balanceReader.balancesOf(wallet).held())
                .isEqualTo(com.sharkpay.money.Money.of(40_000, "KES"));
        assertThat(env.balanceReader.balancesOf(wallet).available())
                .isEqualTo(com.sharkpay.money.Money.of(60_000, "KES"));
    }

    @Test
    void publishesHoldPlacedAndBalanceChanged() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        env.events.reset();
        UUID sourceRef = UUID.randomUUID();

        env.placeHold.place("key-1", wallet.id(), 25_000, "KES", Source.PAYOUTS, sourceRef, null);

        assertThat(env.events.events()).hasSize(2);
        assertThat(env.events.events().get(0).type()).isEqualTo(WalletEvents.HOLD_PLACED);
        assertThat(env.events.events().get(1).type()).isEqualTo(WalletEvents.BALANCE_CHANGED);
        WalletEvents.WalletBalanceData balance =
                (WalletEvents.WalletBalanceData) env.events.events().get(1).data();
        assertThat(balance.balances().held().amount_minor()).isEqualTo(25_000);
        assertThat(balance.balances().available().amount_minor()).isEqualTo(75_000);
        assertThat(balance.source()).isEqualTo(Source.PAYOUTS);
        assertThat(balance.source_ref()).isEqualTo(sourceRef);
    }

    @Test
    void sameKeySamePayloadReplaysTheOriginalHoldWithNoDoubleEffect() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        PlaceHoldUseCase.Result first = env.placeHold.place("key-1", wallet.id(), 40_000, "KES",
                Source.PAYMENTS, UUID.randomUUID(), "note");
        env.events.reset();

        PlaceHoldUseCase.Result replay = env.placeHold.place("key-1", wallet.id(), 40_000, "KES",
                Source.PAYMENTS, first.hold().sourceRef(), "note");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.hold()).isEqualTo(first.hold());
        // NO double effect: still exactly one active hold of 40_000
        assertThat(env.holds.count()).isEqualTo(1);
        assertThat(env.balanceReader.balancesOf(wallet).held().amountMinor()).isEqualTo(40_000);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor()).isEqualTo(60_000);
        assertThat(env.events.events()).isEmpty();
    }

    @Test
    void sameKeyDifferentPayloadIsAConflictWithNoEffect() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        env.placeHold.place("key-1", wallet.id(), 40_000, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null);

        assertThatThrownBy(() -> env.placeHold.place("key-1", wallet.id(), 50_000, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("key-1");
        // no second hold, no reservation change
        assertThat(env.holds.count()).isEqualTo(1);
        assertThat(env.balanceReader.balancesOf(wallet).held().amountMinor()).isEqualTo(40_000);
    }

    @Test
    void reservationLargerThanAvailableIsRejected() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);

        assertThatThrownBy(() -> env.placeHold.place("key-1", wallet.id(), 100_001, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("insufficient funds");
        assertThat(env.holds.count()).isZero();
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                .isEqualTo(100_000);
    }

    @Test
    void theNonNegativeAvailableInvariantHoldsUnderEverySequence() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 1_000);

        // hold up to exactly available → available becomes zero, never negative
        PlaceHoldUseCase.Result first = env.placeHold.place("k1", wallet.id(), 600, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null);
        env.placeHold.place("k2", wallet.id(), 400, "KES", Source.TRANSFERS, UUID.randomUUID(), null);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor()).isZero();

        // a third hold of even 1 is rejected
        assertThatThrownBy(() -> env.placeHold.place("k3", wallet.id(), 1, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(env.balanceReader.balancesOf(wallet).available().isNegative()).isFalse();

        // releasing returns funds; available climbs back without ever dipping
        env.releaseHold.release("r1", first.hold().id(), null);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                .isEqualTo(600);

        // a hold spanning released funds works again
        env.placeHold.place("k4", wallet.id(), 600, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor()).isZero();
    }

    @Test
    void holdsCountAgainstAvailableNotTotal() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 1_000);
        env.placeHold.place("k1", wallet.id(), 1_000, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null);

        // total is still 1_000 but available is 0 — another hold is rejected
        assertThatThrownBy(() -> env.placeHold.place("k2", wallet.id(), 1, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("available 0");
    }

    @Test
    void partialCaptureFreesItsRemainderForNewHolds() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 1_000);
        PlaceHoldUseCase.Result hold = env.placeHold.place("k1", wallet.id(), 800, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null);
        env.captureHold.capture("c1", hold.hold().id(), 500L, null);

        // 500 captured + 300 released = 800 exactly; the hold is terminal so
        // nothing is held anymore. The captured part settles when its debit
        // arrives on the money feed (the ledger is the sole authority — the
        // wallet service never mutates totals itself)
        assertThat(env.balanceReader.balancesOf(wallet).held().amountMinor()).isZero();

        // the settled debit arrives on the ledger feed: total drops by 500
        env.debit(wallet, 500);
        assertThat(env.balanceReader.balancesOf(wallet).total().amountMinor()).isEqualTo(500);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor()).isEqualTo(500);

        // the released remainder + the remaining funds are spendable again
        env.placeHold.place("k2", wallet.id(), 500, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor()).isZero();
    }

    @Test
    void frozenWalletsRejectNewHolds() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        env.changeStatus.freeze(wallet.id(), "case-9");

        assertThatThrownBy(() -> env.placeHold.place("key-1", wallet.id(), 1, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(WalletStateException.class)
                .hasMessageContaining("frozen");
        assertThat(env.holds.count()).isZero();
    }

    @Test
    void currencyMismatchBetweenAmountAndWalletIsRejected() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);

        assertThatThrownBy(() -> env.placeHold.place("key-1", wallet.id(), 1_000, "USD",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThat(env.holds.count()).isZero();
    }

    @Test
    void unsupportedCurrencyIsRejectedAsValidation() {
        Wallet wallet = env.newWallet("KES");
        assertThatThrownBy(() -> env.placeHold.place("key-1", wallet.id(), 1_000, "XYZ",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(UnknownCurrencyException.class);
    }

    @Test
    void zeroAndNegativeAmountsAreRejected() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        assertThatThrownBy(() -> env.placeHold.place("key-1", wallet.id(), 0, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> env.placeHold.place("key-1", wallet.id(), -5, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(env.holds.count()).isZero();
    }

    @Test
    void unknownWalletIsNotFound() {
        assertThatThrownBy(() -> env.placeHold.place("key-1", "wal_0123456789abcdef0123456789abcdef",
                1, "KES", Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        Wallet wallet = env.newWallet("KES");
        assertThatThrownBy(() -> env.placeHold.place(" ", wallet.id(), 1, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> env.placeHold.place(null, wallet.id(), 1, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailedReservationReleasesItsIdempotencyKey() {
        Wallet wallet = env.newWallet("KES");
        // no ledger credit: the hold will fail with insufficient funds

        assertThatThrownBy(() -> env.placeHold.place("key-1", wallet.id(), 500, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(InsufficientFundsException.class);

        // the key was released: a retry after funding succeeds with the SAME key
        env.credit(wallet, 1_000);
        PlaceHoldUseCase.Result retry = env.placeHold.place("key-1", wallet.id(), 500, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null);
        assertThat(retry.replay()).isFalse();
        assertThat(env.holds.count()).isEqualTo(1);
    }

    @Test
    void overflowRejectionHeldSumNeverOverflowsInt64() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, Long.MAX_VALUE);

        // holds are each ≤ available, so their sum cannot overflow; the
        // path is exercised with a huge single hold
        env.placeHold.place("k1", wallet.id(), Long.MAX_VALUE - 1, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor()).isEqualTo(1);
        assertThat(env.balanceReader.balancesOf(wallet).held().amountMinor())
                .isEqualTo(Long.MAX_VALUE - 1);

        // available computation itself must stay overflow-free
        assertThat(env.balanceReader.balancesOf(wallet).available().isNegative()).isFalse();
    }

    @Test
    void aMissingReferencedHoldOnReplayIsAnError() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 1_000);
        UUID sourceRef = UUID.randomUUID();
        String fingerprint = PlaceHoldUseCase.fingerprint(wallet.id(),
                com.sharkpay.money.Money.of(500, "KES"), Source.PAYMENTS, sourceRef, null);
        env.idempotency.put(com.sharkpay.wallet.ports.IdempotencyStore.Scope.PLACE_HOLD,
                "ghost-key", new com.sharkpay.wallet.ports.IdempotencyStore.StoredRequest(
                        fingerprint, "hld_0123456789abcdef0123456789abcdef"));

        assertThatThrownBy(() -> env.placeHold.place("ghost-key", wallet.id(), 500, "KES",
                Source.PAYMENTS, sourceRef, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("referenced by idempotency key");
    }

    @Test
    void moneyOverflowOnHeldSumIsSurfacedNotWrapped() {
        // a corrupted store holding two near-MAX active holds would overflow
        // the held sum — the BalanceReader must reject, not wrap
        String walletId = "wal_0123456789abcdef0123456789abcdef";
        com.sharkpay.money.Money huge = com.sharkpay.money.Money.of(Long.MAX_VALUE / 2 + 1, "KES");
        env.holds.save(com.sharkpay.wallet.domain.Hold.place(
                "hld_0123456789abcdef0123456789abcde1", walletId, huge, Source.PAYMENTS,
                UUID.randomUUID(), null, env.clock.instant()));
        env.holds.save(com.sharkpay.wallet.domain.Hold.place(
                "hld_0123456789abcdef0123456789abcde2", walletId, huge, Source.PAYMENTS,
                UUID.randomUUID(), null, env.clock.instant()));
        Wallet ghost = com.sharkpay.wallet.domain.Wallet.newWallet(walletId, UUID.randomUUID(),
                "KES", UUID.randomUUID(), env.clock.instant());

        assertThatThrownBy(() -> env.balanceReader.balancesOf(ghost))
                .isInstanceOf(MoneyOverflowException.class);
    }

    @Test
    void manyConcurrentlyActiveHoldsSumExactlyUpToAvailable() {
        // "concurrent holds" = simultaneously ACTIVE reservations: their SUM
        // is the held partition, every one of them counts against the SAME
        // available pool, and the pool never goes negative. (Cross-request
        // serialization of the read-check-place sequence is a storage-adapter
        // responsibility — row locking in the JPA adapter at integration,
        // exactly as the Go ledger guards its entries with triggers.)
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 1_000);

        java.util.List<Hold> active = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            active.add(env.placeHold.place("sum-" + i, wallet.id(), 40, "KES", Source.PAYMENTS,
                    UUID.randomUUID(), null).hold());
        }
        // sum of the 20 coexisting holds == available exactly; nothing left
        assertThat(env.balanceReader.balancesOf(wallet).held().amountMinor()).isEqualTo(800);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                .isEqualTo(200);

        // 5 more holds exhaust the pool to the last minor unit
        for (int i = 0; i < 5; i++) {
            active.add(env.placeHold.place("tail-" + i, wallet.id(), 40, "KES", Source.TRANSFERS,
                    UUID.randomUUID(), null).hold());
        }
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor()).isZero();
        assertThat(env.holds.findActiveByWalletId(wallet.id())).hasSize(25);

        // one more minor unit is impossible — the invariant holds at the edge
        assertThatThrownBy(() -> env.placeHold.place("over", wallet.id(), 1, "KES",
                Source.PAYMENTS, UUID.randomUUID(), null))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(env.balanceReader.balancesOf(wallet).available().isNegative()).isFalse();

        // releasing ONE of the coexisting holds frees exactly its amount
        env.releaseHold.release("free-1", active.get(7).id(), null);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor()).isEqualTo(40);
        assertThat(env.balanceReader.balancesOf(wallet).held().amountMinor()).isEqualTo(960);
    }

    @Test
    void randomizedOperationSequencesNeverBreakTheInvariant() {
        // deterministic random walk (fixed seed) over the wallet-mediated
        // operations: after EVERY step, available >= 0,
        // held == sum(active holds), available == total - held.
        java.util.Random random = new java.util.Random(20260901L);
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 10_000);
        java.util.List<Hold> holds = new java.util.ArrayList<>();
        int[] key = {0};

        for (int step = 0; step < 200; step++) {
            switch (random.nextInt(4)) {
                case 0 -> {   // ledger money in
                    env.credit(wallet, 100 + random.nextInt(500));
                }
                case 1 -> {   // place a hold that may legitimately fail
                    long available =
                            env.balanceReader.balancesOf(wallet).available().amountMinor();
                    long amount = 1 + random.nextInt(1_000);
                    try {
                        holds.add(env.placeHold.place("rnd-" + key[0]++, wallet.id(), amount, "KES",
                                Source.PAYMENTS, UUID.randomUUID(), null).hold());
                    } catch (InsufficientFundsException expected) {
                        assertThat(amount).isGreaterThan(available);
                    }
                }
                case 2 -> {   // release a random active hold
                    holds.stream()
                            .filter(hold -> hold.state() == HoldState.ACTIVE)
                            .skip(holds.isEmpty() ? 0 : random.nextInt(
                                    Math.max(1, (int) holds.stream()
                                            .filter(hold -> hold.state() == HoldState.ACTIVE)
                                            .count())))
                            .findFirst()
                            .ifPresent(hold -> env.releaseHold.release("rnd-" + key[0]++,
                                    hold.id(), null));
                }
                default -> {   // partial capture of a random active hold
                    holds.stream()
                            .filter(hold -> hold.state() == HoldState.ACTIVE)
                            .findFirst()
                            .ifPresent(hold -> env.captureHold.capture("rnd-" + key[0]++,
                                    hold.id(), 1L + random.nextInt(
                                            (int) hold.amount().amountMinor()), null));
                }
            }
            long total = env.balanceReader.balancesOf(wallet).total().amountMinor();
            long held = env.balanceReader.balancesOf(wallet).held().amountMinor();
            long available = env.balanceReader.balancesOf(wallet).available().amountMinor();
            long activeSum = env.holds.findActiveByWalletId(wallet.id()).stream()
                    .mapToLong(hold -> hold.amount().amountMinor()).sum();
            assertThat(available).isNotNegative();
            assertThat(held).isEqualTo(activeSum);
            assertThat(available).isEqualTo(total - held);
        }
    }
}
