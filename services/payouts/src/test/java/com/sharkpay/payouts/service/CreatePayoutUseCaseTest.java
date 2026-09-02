package com.sharkpay.payouts.service;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.payouts.domain.Destination;
import com.sharkpay.payouts.domain.IdempotencyConflictException;
import com.sharkpay.payouts.domain.InsufficientFundsException;
import com.sharkpay.payouts.domain.KycRequiredException;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.PrincipalNotActiveException;
import com.sharkpay.payouts.domain.UnknownWalletException;
import com.sharkpay.payouts.domain.WalletFrozenException;
import com.sharkpay.payouts.events.PayoutEvents;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.PrincipalLookup;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CreatePayoutUseCase — validation cascade (wallet, principal, KYC, funds),
 * the single atomic hold posting (amount + fee, wallet → clearing), the
 * response-state contract (PENDING_RISK on acceptance, terminal FAILED on
 * an early ledger rejection), the TTL/release windows and exact
 * idempotency semantics.
 */
class CreatePayoutUseCaseTest {

    private final PayoutsTestEnv env = new PayoutsTestEnv();

    @Test
    void anAcceptedPayoutHoldsAmountPlusFeeAndIsPendingRisk() {
        Payout payout = env.createDefaultPayout(); // KES 500000 mpesa

        assertThat(payout.state()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(payout.amount()).isEqualTo(com.sharkpay.money.Money.of(500_000, "KES"));
        assertThat(payout.fee()).isEqualTo(com.sharkpay.money.Money.of(10_500, "KES"));
        assertThat(payout.nonRefundableFee()).isEqualTo(
                com.sharkpay.money.Money.of(5_500, "KES"));
        assertThat(payout.walletLedgerAccountId()).isEqualTo(env.walletAccount);
        assertThat(payout.expiresAt()).isEqualTo(PayoutsTestEnv.START.plusSeconds(900));
        assertThat(payout.executeAfter()).isEqualTo(PayoutsTestEnv.START);
        assertThat(payout.id()).startsWith("pot_").hasSize(36);

        // ONE atomic 2-leg hold entry: wallet (amount+fee) → clearing
        assertThat(env.ledger.journal()).hasSize(1);
        var hold = env.ledger.entry("payouts:" + payout.id() + ":hold").orElseThrow();
        assertThat(hold.entryType()).isEqualTo(LedgerPort.EntryType.HOLD);
        assertThat(hold.sourceRef()).isEqualTo(payout.internalRef());
        assertThat(hold.legs()).hasSize(2);
        assertThat(hold.legs().get(0).accountRef()).isEqualTo(env.walletAccount.toString());
        assertThat(hold.legs().get(0).amount()).isEqualTo(
                com.sharkpay.money.Money.of(510_500, "KES"));
        assertThat(hold.legs().get(1).accountRef()).isEqualTo("payouts-clearing:KES");
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE - 510_500);
        assertThat(env.ledger.balanceOf("payouts-clearing:KES", "KES")).isEqualTo(510_500);

        // scheduler wake-up + created event + idempotency record
        assertThat(env.scheduler.requestOf(payout.id()).executeAfter())
                .isEqualTo(payout.executeAfter());
        assertThat(env.events.eventsOfType(PayoutEvents.CREATED)).hasSize(1);
        assertThat(env.idempotency.contains(
                com.sharkpay.payouts.ports.IdempotencyStore.Scope.CREATE_PAYOUT, "key-1"))
                .isTrue();
        assertThat(env.payouts.transitionsOf(payout.id())).hasSize(1); // CREATED→PENDING_RISK
    }

    @Test
    void anOnChainPayoutChargesTheOnChainScheduleInUsdc() {
        UUID account = UUID.randomUUID();
        env.registerWallet("wal_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", env.principalId, "USDC",
                30_000_000, account);

        Payout payout = env.createPayout.create("k1",
                "wal_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 25_000_000, "USDC",
                PayoutsTestEnv.onChainDestination(), "on_chain", Map.of(), null, null).payout();

        assertThat(payout.state()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(payout.fee()).isEqualTo(com.sharkpay.money.Money.of(312_500, "USDC"));
        assertThat(payout.nonRefundableFee()).isEqualTo(
                com.sharkpay.money.Money.of(250_000, "USDC"));
        assertThat(env.ledger.legsOf("payouts:" + payout.id() + ":hold").get(1).accountRef())
                .isEqualTo("payouts-clearing:USDC");
    }

    @Test
    void theIdempotencyKeyReplaysTheOriginalPayoutWithoutASecondHold() {
        Payout first = env.createPayout("replay-key");
        int journalBefore = env.ledger.journal().size();
        int eventsBefore = env.events.count();

        CreatePayoutUseCase.Result replay = env.createPayout.create("replay-key",
                PayoutsTestEnv.WALLET, 500_000L, "KES", PayoutsTestEnv.mpesaDestination(), null,
                Map.of(), null, null);

        assertThat(replay.replay()).isTrue();
        assertThat(replay.payout().id()).isEqualTo(first.id());
        assertThat(env.ledger.journal()).hasSize(journalBefore);
        assertThat(env.events.count()).isEqualTo(eventsBefore);
        assertThat(env.ledger.totalEffects()).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentPayloadIsA409() {
        env.createPayout("conflict");
        assertThatThrownBy(() -> env.createPayout.create("conflict", PayoutsTestEnv.WALLET,
                500_001L, "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> env.createPayout.create("conflict", PayoutsTestEnv.WALLET,
                500_000L, "KES", PayoutsTestEnv.bankDestination(), null, Map.of(), null, null))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aBlankIdempotencyKeyIsRejectedBeforeAnythingHappens() {
        for (String bad : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> env.createPayout.create(bad, PayoutsTestEnv.WALLET, 1_000L,
                    "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                    .as("key %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Idempotency-Key header must not be blank");
        }
    }

    @Test
    void validationRejectionsCreateNothing() {
        // unknown wallet → 404
        assertThatThrownBy(() -> env.createPayout.create("k-unknown",
                "wal_0000000000000000000000000", 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(UnknownWalletException.class);
        // frozen wallet → 422
        env.wallets.freeze(PayoutsTestEnv.WALLET);
        assertThatThrownBy(() -> env.createPayout.create("k-frozen", PayoutsTestEnv.WALLET, 1_000L,
                "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(WalletFrozenException.class);
        env.wallets.addWallet(PayoutsTestEnv.WALLET, env.principalId, "KES",
                PayoutsTestEnv.DEFAULT_BALANCE, env.walletAccount);
        // currency mismatch vs wallet → 422
        assertThatThrownBy(() -> env.createPayout.create("k-ccy", PayoutsTestEnv.WALLET, 1_000L,
                "USD", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(CurrencyMismatchException.class);
        // rail/currency incompatibility → 422 unsupported_destination
        assertThatThrownBy(() -> env.createPayout.create("k-rail", PayoutsTestEnv.WALLET, 1_000L,
                "KES", PayoutsTestEnv.onChainDestination(), null, Map.of(), null, null))
                .isInstanceOf(com.sharkpay.payouts.domain.UnsupportedDestinationException.class)
                .hasMessageContaining("does not support currency KES");
        // incompatible rail hint → 422
        assertThatThrownBy(() -> env.createPayout.create("k-hint", PayoutsTestEnv.WALLET, 1_000L,
                "KES", PayoutsTestEnv.mpesaDestination(), "bank", Map.of(), null, null))
                .isInstanceOf(com.sharkpay.payouts.domain.UnsupportedDestinationException.class)
                .hasMessageContaining("not compatible");
        // unknown rail hint → 400
        assertThatThrownBy(() -> env.createPayout.create("k-hint2", PayoutsTestEnv.WALLET, 1_000L,
                "KES", PayoutsTestEnv.mpesaDestination(), "pesa", Map.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown rail");

        assertThat(env.ledger.journal()).isEmpty();
        assertThat(env.payouts.count()).isZero();
        assertThat(env.events.count()).isZero();
        assertThat(env.idempotency.count()).isZero();
    }

    @Test
    void anUnregisteredPrincipalSurfacesLoudly() {
        UUID unknownPrincipal = UUID.randomUUID();
        env.registerWallet("wal_dddddddddddddddddddddddddddddddd", unknownPrincipal, "KES",
                1_000_000, UUID.randomUUID());
        assertThatThrownBy(() -> env.createPayout.create("k1",
                "wal_dddddddddddddddddddddddddddddddd", 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("principal")
                .hasMessageContaining(unknownPrincipal.toString());
    }

    @Test
    void thePrincipalMustBeActiveWithAtLeastLimitedKyc() {
        env.principals.add(env.principalId,
                PrincipalLookup.PrincipalStatus.SUSPENDED, PrincipalLookup.KycTier.FULL);
        assertThatThrownBy(() -> env.createPayout.create("k-susp", PayoutsTestEnv.WALLET, 1_000L,
                "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(PrincipalNotActiveException.class)
                .hasMessageContaining("SUSPENDED");

        env.principals.add(env.principalId, PrincipalLookup.PrincipalStatus.ACTIVE,
                PrincipalLookup.KycTier.UNVERIFIED);
        assertThatThrownBy(() -> env.createPayout.create("k-kyc", PayoutsTestEnv.WALLET, 1_000L,
                "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(KycRequiredException.class);

        // LIMITED and FULL are both payout-capable
        env.principals.add(env.principalId, PrincipalLookup.PrincipalStatus.ACTIVE,
                PrincipalLookup.KycTier.LIMITED);
        assertThat(env.createPayout.create("k-lim", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null).payout().state())
                .isEqualTo(PayoutState.PENDING_RISK);
        env.principals.add(env.principalId, PrincipalLookup.PrincipalStatus.ACTIVE,
                PrincipalLookup.KycTier.FULL);
        assertThat(env.createPayout.create("k-full", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null).payout().state())
                .isEqualTo(PayoutState.PENDING_RISK);
        // none of the rejections moved money
        assertThat(env.ledger.journal()).hasSize(2);
    }

    @Test
    void insufficientAvailableBalanceCoversAmountPlusFee() {
        // 500000 + 10500 = 510500: one minor short fails
        long almost = 510_500 - 1;
        env.registerWallet("wal_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", env.principalId, "KES",
                almost, UUID.randomUUID());
        assertThatThrownBy(() -> env.createPayout.create("k1",
                "wal_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 500_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining(String.valueOf(almost))
                .hasMessageContaining("510500");
        // exactly amount + fee is accepted
        env.registerWallet("wal_cccccccccccccccccccccccccccccccc", env.principalId, "KES",
                510_500, UUID.randomUUID());
        assertThat(env.createPayout.create("k2", "wal_cccccccccccccccccccccccccccccccc",
                500_000L, "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null)
                .payout().state()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(env.ledger.journal()).hasSize(1);
    }

    @Test
    void anAmountOverflowingTheFeeQuoteIsRejectedAsMoneyOverflow() {
        assertThatThrownBy(() -> env.createPayout.create("k1", PayoutsTestEnv.WALLET,
                Long.MAX_VALUE, "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null,
                null))
                .isInstanceOf(com.sharkpay.money.MoneyOverflowException.class)
                .hasMessageContaining("fee computation overflow");
        assertThat(env.ledger.journal()).isEmpty();
    }

    @Test
    void aLedgerRejectionReturnsTerminalFailedWithNoHoldLanded() {
        env.ledger.rejectPrefix("payouts:");

        CreatePayoutUseCase.Result result = env.createPayout.create("k1", PayoutsTestEnv.WALLET,
                500_000L, "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null);

        assertThat(result.payout().state()).isEqualTo(PayoutState.FAILED);
        assertThat(result.payout().failureReason()).contains("insufficient_funds");
        assertThat(result.payout().holdEntryId()).isNull();
        assertThat(env.ledger.journal()).isEmpty(); // no hold landed, funds never moved
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE);
        assertThat(env.events.eventsOfType(PayoutEvents.FAILED)).hasSize(1);
        // the retry replays the FAILED original (no second attempt)
        CreatePayoutUseCase.Result replay = env.createPayout.create("k1", PayoutsTestEnv.WALLET,
                500_000L, "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.payout().state()).isEqualTo(PayoutState.FAILED);
        assertThat(env.ledger.journal()).isEmpty();
    }

    @Test
    void aLedgerPortFailurePropagatesAndReleasesTheKeyReservation() {
        env.ledger.failPrefix("payouts:", new RuntimeException("ledger unreachable"));

        assertThatThrownBy(() -> env.createPayout.create("k1", PayoutsTestEnv.WALLET, 1_000L,
                "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ledger unreachable");
        assertThat(env.ledger.journal()).isEmpty();
        assertThat(env.idempotency.count()).isZero();
        assertThat(env.scheduler.requests()).isEmpty();
    }

    @Test
    void theTtlDefaultsTo900SecondsAndHonorsTheConfiguredWindow() {
        Payout payout = env.createPayout.create("k1", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null).payout();
        assertThat(payout.expiresAt()).isEqualTo(PayoutsTestEnv.START.plus(
                CreatePayoutUseCase.DEFAULT_TTL));

        Payout custom = env.createPayout.create("k2", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), 60, null).payout();
        assertThat(custom.expiresAt()).isEqualTo(PayoutsTestEnv.START.plusSeconds(60));

        for (int bad : new int[]{59, -1, 86_401}) {
            assertThatThrownBy(() -> env.createPayout.create("k-bad", PayoutsTestEnv.WALLET,
                    1_000L, "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), bad, null))
                    .as("expires_in_seconds=%d", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expires_in_seconds");
        }
    }

    @Test
    void aFutureExecuteAfterSchedulesTheReleaseAndAShortTtlIsExtended() {
        Instant release = PayoutsTestEnv.START.plusSeconds(1_200);

        Payout payout = env.createPayout.create("k1", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, release).payout();

        assertThat(payout.executeAfter()).isEqualTo(release);
        assertThat(payout.expiresAt()).isEqualTo(release.plusSeconds(60)); // ttl extended
        assertThat(payout.dueForRelease(PayoutsTestEnv.START)).isFalse();
        assertThat(env.scheduler.requestOf(payout.id()).executeAfter()).isEqualTo(release);
    }

    @Test
    void aPastExecuteAfterIsClampedToNow() {
        Payout payout = env.createPayout.create("k1", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null,
                PayoutsTestEnv.START.minusSeconds(60)).payout();
        assertThat(payout.executeAfter()).isEqualTo(PayoutsTestEnv.START);
    }

    @Test
    void aReplayWhoseOriginalPayoutDisappearedSurfacesLoudly() {
        env.createPayout("lost");
        String payoutId = env.idempotency.find(
                com.sharkpay.payouts.ports.IdempotencyStore.Scope.CREATE_PAYOUT, "lost")
                .orElseThrow().entityId();
        env.payouts.remove(payoutId);

        assertThatThrownBy(() -> env.createPayout.create("lost", PayoutsTestEnv.WALLET, 500_000L,
                "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("is missing");
    }

    @Test
    void theFingerprintCapturesWalletAmountCurrencyDestinationAndRail() {
        Destination destination = PayoutsTestEnv.mpesaDestination();
        assertThat(CreatePayoutUseCase.fingerprint(PayoutsTestEnv.WALLET,
                com.sharkpay.money.Money.of(500_000, "KES"), destination,
                com.sharkpay.payouts.domain.Rail.MPESA))
                .isEqualTo("CREATE_PAYOUT|" + PayoutsTestEnv.WALLET + "|500000|KES|"
                        + destination.describe() + "|mpesa");
    }

    @Test
    void defaultConstantsMatchTheContract() {
        assertThat(CreatePayoutUseCase.DEFAULT_TTL).isEqualTo(Duration.ofSeconds(900));
    }
}
