package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saga compensation: every failure path releases the hold (wallet + ledger
 * RELEASE entry — never an in-place mutation) and lands in FAILED, exactly
 * once (ADR 003 G2).
 */
class FailPaymentUseCaseTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void releasesTheHoldAndFailsWithTheReason() {
        PaymentIntent intent = env.createDefault();
        env.events.reset();

        var result = env.failPayment.fail(intent.id(), "provider_rejected");

        assertThat(result.skipped()).isFalse();
        assertThat(result.intent().state()).isEqualTo(PaymentState.FAILED);
        assertThat(result.intent().failureReason()).isEqualTo("provider_rejected");
        assertThat(result.intent().releaseEntryId()).isNotNull();

        // compensation: wallet release + ledger RELEASE entry, exactly one each
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.CAPTURE))
                .isZero();

        // the FAILED event carries the release entry id
        var event = env.events.eventsOfType("payments.payment.failed.v1").get(0);
        var data = (com.sharkpay.payments.events.PaymentEvents.PaymentData) event.data();
        assertThat(data.reason()).isEqualTo("provider_rejected");
        assertThat(data.entry_id()).isEqualTo(result.intent().releaseEntryId());
    }

    @Test
    void compensationRunsExactlyOncePerFailurePath() {
        PaymentIntent intent = env.createDefault();

        env.failPayment.fail(intent.id(), "provider_rejected");
        env.failPayment.fail(intent.id(), "provider_rejected"); // at-least-once delivery

        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1); // journal still has exactly one RELEASE row
        assertThat(env.ledger.attemptCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1); // and the second call never even attempted
        assertThat(env.events.eventsOfType("payments.payment.failed.v1")).hasSize(1);
    }

    @Test
    void terminalIntentsAreSkipped() {
        env.gateway.rejectNextInitiations(1);
        PaymentIntent failed = env.createDefault(); // already FAILED via create

        var result = env.failPayment.fail(failed.id(), "another");

        assertThat(result.skipped()).isTrue();
        assertThat(result.intent().failureReason()).isEqualTo(failed.failureReason());
    }

    @Test
    void intentsWithoutAHoldFailWithoutReleaseEntries() {
        // rehydrated edge case: PENDING_PROVIDER with a lost hold ref — the
        // failure still lands, with no release entry and no wallet release
        PaymentIntent intent = com.sharkpay.payments.domain.PaymentIntent.rehydrate(
                "pay_0123456789abcdef0123456789abcdef", java.util.UUID.randomUUID(),
                env.principals.principalId(), null,
                com.sharkpay.payments.domain.Destination.internalWallet(PaymentsTestEnv.WALLET),
                com.sharkpay.money.Money.of(150_000, "KES"),
                com.sharkpay.money.Money.of(750, "KES"),
                com.sharkpay.payments.domain.Rail.HONEYCOIN,
                PaymentState.PENDING_PROVIDER, "k", PaymentsTestEnv.START.plusSeconds(900),
                java.util.Map.of(), null, null, null, null, null, null, null, null, null,
                PaymentsTestEnv.START, PaymentsTestEnv.START, 2);
        env.payments.save(intent);

        var result = env.failPayment.fail(intent.id(), "provider_rejected");

        assertThat(result.intent().state()).isEqualTo(PaymentState.FAILED);
        assertThat(result.intent().releaseEntryId()).isNull();
        assertThat(env.ledger.totalEffects()).isZero();
    }

    @Test
    void failureFromProcessingReleasesTheHoldToo() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "PROCESSING");

        env.failPayment.fail(intent.id(), "rail_failure");

        assertThat(intent.state()).isEqualTo(PaymentState.FAILED);
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
    }

    @Test
    void reasonIsRequiredAndUnknownPayments404() {
        assertThatThrownBy(() -> env.failPayment.fail("pay_0123456789abcdef0123456789abcdee",
                "r")).isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class);
        PaymentIntent intent = env.createDefault();
        assertThatThrownBy(() -> env.failPayment.fail(intent.id(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
