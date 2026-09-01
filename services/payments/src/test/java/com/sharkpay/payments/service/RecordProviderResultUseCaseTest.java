package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.IdempotencyConflictException;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.PaymentStateException;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.ports.RiskPort;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider-result application (confirm → capture): the §1 guard "SUCCEEDED
 * is reachable only after risk post-evaluation passed", compensation on
 * failure, PENDING/UNKNOWN parking, and the no-double-capture guarantee
 * (ADR 003 G2).
 */
class RecordProviderResultUseCaseTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void processingTransitionsPendingProviderToProcessing() {
        PaymentIntent intent = env.createDefault();
        env.events.reset();

        var result = env.recordResult.record(null, intent.id(), "PROCESSING");

        assertThat(result.intent().state()).isEqualTo(PaymentState.PROCESSING);
        assertThat(env.ledger.totalEffects()).isEqualTo(1); // HOLD only, no money moved
        assertThat(env.events.events()).isEmpty(); // no catalog type for PROCESSING

        // idempotent: a second PROCESSING result is a no-op
        env.recordResult.record(null, intent.id(), "PROCESSING");
        assertThat(env.payments.transitionsOf(intent.id()))
                .filteredOn(row -> row.to() == PaymentState.PROCESSING).hasSize(1);
    }

    @Test
    void succeededCapturesTheHoldAfterPostAuthorizationRiskPasses() {
        PaymentIntent intent = env.createDefault();
        env.events.reset();

        var result = env.recordResult.record(null, intent.id(), "SUCCEEDED");

        assertThat(result.intent().state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(result.intent().captureEntryId()).isNotNull();

        // §1 guard: post-authorization risk ran with the right phase
        assertThat(env.risk.evaluations()).hasSize(2);
        assertThat(env.risk.evaluations().get(1).phase())
                .isEqualTo(RiskPort.Phase.POST_AUTHORIZATION);
        assertThat(env.risk.evaluations().get(1).paymentId()).isEqualTo(intent.id());

        // capture: ledger CAPTURE entry + wallet capture, exactly once each
        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.CAPTURE))
                .isEqualTo(1);
        assertThat(env.walletHolds.capturedHolds()).hasSize(1);
        assertThat(env.walletHolds.capturedHolds().get(intent.internalId()).amountMinor())
                .isEqualTo(150_000);

        var event = env.events.eventsOfType("payments.payment.succeeded.v1").get(0);
        var data = (com.sharkpay.payments.events.PaymentEvents.PaymentData) event.data();
        assertThat(data.state()).isEqualTo("SUCCEEDED");
        assertThat(data.entry_id()).isEqualTo(result.intent().captureEntryId());
        assertThat(data.provider_ref()).isEqualTo(intent.providerRef());
    }

    @Test
    void postAuthorizationRiskDenyFailsClosedWithoutCapturing() {
        PaymentIntent intent = env.createDefault();
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.deny("post-auth velocity"));

        var result = env.recordResult.record(null, intent.id(), "SUCCEEDED");

        assertThat(result.intent().state()).isEqualTo(PaymentState.FAILED);
        assertThat(result.intent().failureReason()).contains("post_authorization_risk");
        // NO capture happened anywhere (G2: no capture after a risk deny)
        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.CAPTURE))
                .isZero();
        assertThat(env.walletHolds.capturedHolds()).isEmpty();
        // the hold was compensated instead
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1);
    }

    @Test
    void providerFailedAndReturnedBothCompensate() {
        PaymentIntent intent = env.createDefault();
        var failed = env.recordResult.record(null, intent.id(), "FAILED");
        assertThat(failed.intent().state()).isEqualTo(PaymentState.FAILED);
        assertThat(failed.intent().failureReason()).isEqualTo("provider_failed");
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();

        PaymentIntent returned = env.create("k-2");
        var result = env.recordResult.record(null, returned.id(), "RETURNED");
        assertThat(result.intent().state()).isEqualTo(PaymentState.FAILED);
        assertThat(result.intent().failureReason()).isEqualTo("provider_returned");
    }

    @Test
    void pendingAndUnknownParkWithoutAnyTransition() {
        PaymentIntent intent = env.createDefault();

        env.recordResult.record(null, intent.id(), "PENDING");
        env.recordResult.record(null, intent.id(), "UNKNOWN");

        assertThat(intent.state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(env.ledger.totalEffects()).isEqualTo(1); // HOLD only
        assertThat(env.events.events()).hasSize(2);          // created + pending_provider
        assertThat(env.payments.transitionsOf(intent.id())).hasSize(2);
    }

    @Test
    void aSecondSucceededResultCanNeverDoubleCapture() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");

        // duplicate delivery of the same terminal result (poll + callback)
        env.recordResult.record(null, intent.id(), "SUCCEEDED");

        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.CAPTURE))
                .isEqualTo(1);
        assertThat(env.ledger.attemptCount(intent.internalId(), LedgerPort.EntryType.CAPTURE))
                .isEqualTo(1);
        assertThat(env.walletHolds.capturedHolds()).hasSize(1);
        assertThat(env.events.eventsOfType("payments.payment.succeeded.v1")).hasSize(1);
    }

    @Test
    void succeededResultOnATerminalNonCapturableIntentIsAStateConflict() {
        PaymentIntent intent = env.createDefault();
        env.cancelPayment.cancel("ck", intent.id());

        assertThatThrownBy(() -> env.recordResult.record(null, intent.id(), "SUCCEEDED"))
                .isInstanceOf(PaymentStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void failedResultOnATerminalIntentIsAnIdempotentNoOp() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "FAILED");

        var again = env.recordResult.record(null, intent.id(), "FAILED");

        assertThat(again.intent().state()).isEqualTo(PaymentState.FAILED);
        assertThat(env.ledger.attemptCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1);
    }

    @Test
    void idempotentReplayWithAKeyReturnsTheOriginalOutcome() {
        PaymentIntent intent = env.createDefault();
        var first = env.recordResult.record("irk-1", intent.id(), "SUCCEEDED");
        env.events.reset();

        var replay = env.recordResult.record("irk-1", intent.id(), "SUCCEEDED");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.intent().id()).isEqualTo(first.intent().id());
        assertThat(env.ledger.attemptCount(intent.internalId(), LedgerPort.EntryType.CAPTURE))
                .isEqualTo(1);
        assertThat(env.events.events()).isEmpty();
    }

    @Test
    void sameKeyDifferentResultIsAConflict() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record("irk-1", intent.id(), "SUCCEEDED");

        assertThatThrownBy(() -> env.recordResult.record("irk-1", intent.id(), "FAILED"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void blankKeysAreIgnoredAndUnknownStatusesAreRejected() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record("  ", intent.id(), "PENDING"); // blank = no key
        assertThat(env.idempotency.find(
                com.sharkpay.payments.ports.IdempotencyStore.Scope.PROVIDER_RESULT, "  "))
                .isEmpty();

        assertThatThrownBy(() -> env.recordResult.record(null, intent.id(), "BOGUS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown provider status");
        assertThatThrownBy(() -> env.recordResult.record(null, intent.id(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void unknownPaymentIsNotFound() {
        assertThatThrownBy(() -> env.recordResult.record(null,
                "pay_0123456789abcdef0123456789abcdee", "SUCCEEDED"))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class);
    }

    @Test
    void confirmOnAnExternalDestinationFailsClosedBeforeAnyCapture() {
        // a rehydrated external-rail intent has no wallet to capture into —
        // fail closed BEFORE the capture entry or wallet capture, never
        // half-way (money state stays aligned, §7.4)
        PaymentIntent external = com.sharkpay.payments.domain.PaymentIntent.rehydrate(
                env.randomness.paymentId(), java.util.UUID.randomUUID(),
                env.principals.principalId(), null,
                com.sharkpay.payments.domain.Destination.externalRail("msisdn:+254712345678"),
                com.sharkpay.money.Money.of(150_000, "KES"),
                com.sharkpay.money.Money.of(750, "KES"),
                com.sharkpay.payments.domain.Rail.HONEYCOIN,
                PaymentState.PENDING_PROVIDER, "k-external",
                PaymentsTestEnv.START.plusSeconds(900), java.util.Map.of(), "honeycoin",
                "hc_external", "hld_external", null, null, null, null, null, null,
                PaymentsTestEnv.START, PaymentsTestEnv.START, 1);
        env.payments.save(external);
        env.recordResult.record(null, external.id(), "PROCESSING"); // PROCESSING is safe

        assertThatThrownBy(() -> env.recordResult.record(null, external.id(), "SUCCEEDED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no internal wallet destination");
        assertThat(env.ledger.effectCount(external.internalId(), LedgerPort.EntryType.CAPTURE))
                .isZero();
        assertThat(env.walletHolds.capturedHolds()).isEmpty();
    }

    @Test
    void aKeyReplayWhoseIntentVanishedIsANotFoundNotASilentRetry() {
        // storage lost the row the idempotency key points at: the replay
        // must surface 404, never re-apply the provider result
        PaymentIntent intent = env.createDefault();
        env.recordResult.record("irk-gone", intent.id(), "SUCCEEDED");
        env.payments.remove(intent.id());

        assertThatThrownBy(() -> env.recordResult.record("irk-gone", intent.id(), "SUCCEEDED"))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class)
                .hasMessageContaining(intent.id());
        assertThat(env.walletHolds.capturedHolds()).hasSize(1); // nothing re-captured
    }
}
