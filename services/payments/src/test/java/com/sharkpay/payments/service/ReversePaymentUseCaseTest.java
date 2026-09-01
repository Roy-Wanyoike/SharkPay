package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.IdempotencyConflictException;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.PaymentStateException;
import com.sharkpay.payments.domain.ReversalExceedsCapturedException;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reversal (STATE-MACHINES.md §1: SUCCEEDED → REVERSED / FAILED → REVERSED
 * "late funds recovered"): the guard "reversal amount ≤ captured amount",
 * the ledger compensation pair and idempotency.
 */
class ReversePaymentUseCaseTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void reversesASucceededPaymentAgainstItsCaptureEntry() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");
        env.events.reset();

        var result = env.reversePayment.reverse("rk-1", intent.id(), null, "provider reversal");

        assertThat(result.intent().state()).isEqualTo(PaymentState.REVERSED);
        assertThat(result.intent().reversedAmount().amountMinor()).isEqualTo(150_000);
        // the compensation reverses the CAPTURE entry — never a mutation
        assertThat(env.ledger.reversalOfEntry(intent.captureEntryId())).isNotNull();
        assertThat(env.ledger.reversalOfEntry(intent.captureEntryId()))
                .isEqualTo(result.intent().reversalEntryId());

        var event = env.events.eventsOfType("payments.payment.reversed.v1").get(0);
        var data = (com.sharkpay.payments.events.PaymentEvents.PaymentData) event.data();
        assertThat(data.reason()).isEqualTo("provider reversal");
        assertThat(data.entry_id()).isEqualTo(result.intent().reversalEntryId());
    }

    @Test
    void reversesAFailedPaymentWithALateFundsCompensationEntry() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "FAILED");

        var result = env.reversePayment.reverse("rk-1", intent.id(), null, "late funds recovered");

        assertThat(result.intent().state()).isEqualTo(PaymentState.REVERSED);
        // late-funds path: a standalone REVERSAL entry (no capture to undo)
        assertThat(env.ledger.entryIdOf(intent.internalId(), LedgerPort.EntryType.REVERSAL))
                .isEqualTo(result.intent().reversalEntryId());
        assertThat(intent.captureEntryId()).isNull();
    }

    @Test
    void partialReversalsCarryTheirAmount() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");

        var result = env.reversePayment.reverse("rk-1", intent.id(), 60_000L, "partial");

        assertThat(result.intent().reversedAmount().amountMinor()).isEqualTo(60_000);
        assertThat(result.intent().amount().amountMinor()).isEqualTo(150_000); // untouched
    }

    @Test
    void reversalAboveTheCapturedAmountIsRejected() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");

        assertThatThrownBy(() -> env.reversePayment.reverse("rk-1", intent.id(), 150_001L, "x"))
                .isInstanceOf(ReversalExceedsCapturedException.class)
                .hasMessageContaining(intent.id());
        assertThatThrownBy(() -> env.reversePayment.reverse("rk-2", intent.id(), -1L, "x"))
                .isInstanceOf(ReversalExceedsCapturedException.class);
    }

    @Test
    void onlySucceededAndFailedIntentsAreReversible() {
        PaymentIntent intent = env.createDefault();
        assertThatThrownBy(() -> env.reversePayment.reverse("rk-1", intent.id(), null, "x"))
                .isInstanceOf(PaymentStateException.class)
                .hasMessageContaining("PENDING_PROVIDER");
    }

    @Test
    void reversalReplaysAndConflictsByIdempotencyKey() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");

        var first = env.reversePayment.reverse("rk-1", intent.id(), null, "r");
        var replay = env.reversePayment.reverse("rk-1", intent.id(), null, "r");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.intent().reversalEntryId()).isEqualTo(first.intent().reversalEntryId());
        assertThat(env.ledger.reversalOf(intent.internalId())).isEqualTo(first.intent().reversalEntryId());

        assertThatThrownBy(() -> env.reversePayment.reverse("rk-1", intent.id(), 1L, "other"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void blankReasonDefaultsAndUnknownPayments404() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");

        var result = env.reversePayment.reverse("rk-1", intent.id(), null, " ");
        assertThat(result.intent().failureReason()).isNull(); // reason not a failure

        assertThatThrownBy(() -> env.reversePayment.reverse("rk-2",
                "pay_0123456789abcdef0123456789abcdee", null, "x"))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class);
        assertThatThrownBy(() -> env.reversePayment.reverse("  ", intent.id(), null, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void aReplayWhoseIntentVanishedIsANotFoundNotASecondReversal() {
        // storage lost the row the idempotency key points at: the replay
        // must surface 404, never post a second compensation pair
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");
        env.reversePayment.reverse("rk-gone", intent.id(), null, "ops");
        int reversals = env.ledger.totalEffects();
        env.payments.remove(intent.id());

        assertThatThrownBy(() -> env.reversePayment.reverse("rk-gone", intent.id(), null, "ops"))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class)
                .hasMessageContaining(intent.id());
        assertThat(env.ledger.totalEffects()).isEqualTo(reversals); // no second pair
    }
}
