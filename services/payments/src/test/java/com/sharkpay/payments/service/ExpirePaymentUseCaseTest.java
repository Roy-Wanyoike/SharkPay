package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Expiry (STATE-MACHINES.md §1 guard: only from PENDING_PROVIDER): the TTL
 * timer releases the hold exactly once (G2) and lands in EXPIRED.
 */
class ExpirePaymentUseCaseTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void expiresAnUnconfirmedIntentAtItsDeadlineAndReleasesTheHoldOnce() {
        PaymentIntent intent = env.createDefault(); // expires at START + 900s
        env.events.reset();

        env.clock.advance(java.time.Duration.ofSeconds(900));
        var result = env.expirePayment.expire(intent.id());

        assertThat(result.skipped()).isFalse();
        assertThat(result.intent().state()).isEqualTo(PaymentState.EXPIRED);
        assertThat(result.intent().releaseEntryId()).isNotNull();
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1);

        var event = env.events.eventsOfType("payments.payment.expired.v1").get(0);
        var data = (com.sharkpay.payments.events.PaymentEvents.PaymentData) event.data();
        assertThat(data.state()).isEqualTo("EXPIRED");
        assertThat(data.reason()).isEqualTo("ttl_elapsed");
        assertThat(data.entry_id()).isEqualTo(result.intent().releaseEntryId());
    }

    @Test
    void expiringTwiceReleasesTheHoldExactlyOnce() {
        PaymentIntent intent = env.createDefault();
        env.clock.advance(java.time.Duration.ofSeconds(1_000));

        env.expirePayment.expire(intent.id());
        env.expirePayment.expire(intent.id()); // at-least-once delivery

        assertThat(env.ledger.effectCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1);
        assertThat(env.ledger.attemptCount(intent.internalId(), LedgerPort.EntryType.RELEASE))
                .isEqualTo(1);
        assertThat(env.events.eventsOfType("payments.payment.expired.v1")).hasSize(1);
        assertThat(env.payments.transitionsOf(intent.id()))
                .filteredOn(row -> row.to() == PaymentState.EXPIRED).hasSize(1);
    }

    @Test
    void notYetExpiredIntentsAreSkipped() {
        PaymentIntent intent = env.createDefault();
        env.clock.advance(java.time.Duration.ofSeconds(899));

        var result = env.expirePayment.expire(intent.id());

        assertThat(result.skipped()).isTrue();
        assertThat(result.intent().state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(env.walletHolds.placedHolds()).hasSize(1); // hold untouched
        assertThat(env.ledger.totalEffects()).isEqualTo(1);   // HOLD only
    }

    @Test
    void intentsThatConfirmedBeforeTheDeadlineNeverExpire() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");
        env.clock.advance(java.time.Duration.ofSeconds(10_000));

        var result = env.expirePayment.expire(intent.id());

        assertThat(result.skipped()).isTrue();
        assertThat(result.intent().state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(env.walletHolds.capturedHolds()).hasSize(1);
        assertThat(env.events.eventsOfType("payments.payment.expired.v1")).isEmpty();
    }

    @Test
    void failedAndCancelledIntentsSkipExpiry() {
        PaymentIntent cancelled = env.createDefault();
        env.cancelPayment.cancel("ck", cancelled.id());
        env.clock.advance(java.time.Duration.ofSeconds(900));
        assertThat(env.expirePayment.expire(cancelled.id()).skipped()).isTrue();

        env.gateway.rejectNextInitiations(1);
        PaymentIntent failed = env.create("k-failed");
        env.clock.advance(java.time.Duration.ofSeconds(900));
        assertThat(env.expirePayment.expire(failed.id()).skipped()).isTrue();
    }

    @Test
    void unknownPaymentsAreNotFound() {
        assertThatThrownBy(() -> env.expirePayment.expire("pay_0123456789abcdef0123456789abcdee"))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class);
    }
}
