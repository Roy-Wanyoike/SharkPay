package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.IdempotencyConflictException;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.PaymentStateException;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * cancelPayment (payments.yaml): CREATED/PENDING_PROVIDER only, hold
 * released exactly once, idempotent replays and conflicts.
 */
class CancelPaymentUseCaseTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void cancelsAPendingProviderIntentAndReleasesItsHold() {
        PaymentIntent intent = env.createDefault();

        var result = env.cancelPayment.cancel("ck-1", intent.id());

        assertThat(result.replay()).isFalse();
        assertThat(result.intent().state()).isEqualTo(PaymentState.CANCELLED);
        assertThat(result.intent().releaseEntryId()).isNotNull();
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1);
        // no catalog event for CANCELLED in /v1 — audited in transitions only
        assertThat(env.events.events()).hasSize(2); // created + pending_provider from the prefix
    }

    @Test
    void cancelsACreatedIntentWithNoHoldToRelease() {
        PaymentIntent intent = createdIntent();

        var result = env.cancelPayment.cancel("ck-1", intent.id());

        assertThat(result.intent().state()).isEqualTo(PaymentState.CANCELLED);
        assertThat(result.intent().releaseEntryId()).isNull();
        assertThat(env.ledger.totalEffects()).isZero();
    }

    @Test
    void cancellingTwiceReplaysTheOriginalOutcomeWithNoSecondRelease() {
        PaymentIntent intent = env.createDefault();

        var first = env.cancelPayment.cancel("ck-1", intent.id());
        var second = env.cancelPayment.cancel("ck-1", intent.id());

        assertThat(second.replay()).isTrue();
        assertThat(second.intent().id()).isEqualTo(first.intent().id());
        assertThat(env.ledger.attemptCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1);
        assertThat(env.walletHolds.placedHolds()).hasSize(1);
    }

    @Test
    void sameKeyForADifferentPaymentIsAConflict() {
        PaymentIntent a = env.create("k-a");
        PaymentIntent b = env.create("k-b");
        env.cancelPayment.cancel("ck-1", a.id());

        assertThatThrownBy(() -> env.cancelPayment.cancel("ck-1", b.id()))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(b.state()).isEqualTo(PaymentState.PENDING_PROVIDER); // untouched
    }

    @Test
    void confirmedTerminalAndBlockedIntentsAreStateConflicts() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");
        assertThatThrownBy(() -> env.cancelPayment.cancel("ck-1", intent.id()))
                .isInstanceOf(PaymentStateException.class)
                .hasMessageContaining("SUCCEEDED")
                .hasMessageContaining("CANCELLED");

        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.deny("d"));
        PaymentIntent blocked = env.create("k-blocked");
        assertThatThrownBy(() -> env.cancelPayment.cancel("ck-2", blocked.id()))
                .isInstanceOf(PaymentStateException.class);
    }

    @Test
    void cancelFreesAProcessingExhaustedIntentCheck() {
        // CREATED + PENDING_PROVIDER are cancellable; PROCESSING is not
        PaymentIntent processing = env.createDefault();
        env.recordResult.record(null, processing.id(), "PROCESSING");
        assertThatThrownBy(() -> env.cancelPayment.cancel("ck-1", processing.id()))
                .isInstanceOf(PaymentStateException.class);
    }

    @Test
    void unknownPaymentAndBlankKeyAreRejected() {
        assertThatThrownBy(() -> env.cancelPayment.cancel("ck", "pay_0123456789abcdef0123456789abcdee"))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class);
        PaymentIntent intent = env.createDefault();
        assertThatThrownBy(() -> env.cancelPayment.cancel("  ", intent.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void aReplayWhoseIntentVanishedIsANotFoundNotASecondCancellation() {
        // storage lost the row the idempotency key points at: the replay
        // must surface 404, never re-run the release path
        PaymentIntent cancelled = env.createDefault();
        env.cancelPayment.cancel("ck-gone", cancelled.id());
        env.payments.remove(cancelled.id());

        assertThatThrownBy(() -> env.cancelPayment.cancel("ck-gone", cancelled.id()))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class)
                .hasMessageContaining(cancelled.id());
        assertThat(env.walletHolds.placedHolds()).hasSize(1); // nothing re-released
    }

    private PaymentIntent createdIntent() {
        PaymentIntent intent = com.sharkpay.payments.domain.PaymentIntent.newIntent(
                "pay_0123456789abcdef0123456789abcdef", java.util.UUID.randomUUID(),
                env.principals.principalId(), null,
                com.sharkpay.payments.domain.Destination.internalWallet(PaymentsTestEnv.WALLET),
                com.sharkpay.money.Money.of(150_000, "KES"),
                com.sharkpay.money.Money.of(750, "KES"), "k",
                com.sharkpay.payments.domain.Rail.HONEYCOIN,
                PaymentsTestEnv.START.plusSeconds(900), java.util.Map.of(),
                PaymentsTestEnv.START);
        env.payments.save(intent);
        return intent;
    }
}
