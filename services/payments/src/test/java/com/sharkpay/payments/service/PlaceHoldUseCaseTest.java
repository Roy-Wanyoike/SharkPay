package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hold placement activity: ledger HOLD entry → wallet hold →
 * PENDING_PROVIDER + event; idempotent for at-least-once delivery.
 */
class PlaceHoldUseCaseTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void placesTheHoldAndMovesToPendingProvider() {
        PaymentIntent created = createdIntent();

        var result = env.placeHold.place(created.id());

        assertThat(result.skipped()).isFalse();
        assertThat(result.holdId()).isNotBlank();
        assertThat(result.intent().state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(result.intent().holdId()).isEqualTo(result.holdId());
        assertThat(result.intent().holdEntryId()).isNotNull();

        // money state alignment: HOLD entry posted for the payment's amount
        assertThat(env.ledger.effectCount(created.internalId(), LedgerPort.EntryType.HOLD))
                .isEqualTo(1);
        assertThat(env.ledger.amountOf(created.internalId(), LedgerPort.EntryType.HOLD)
                .amountMinor()).isEqualTo(150_000);
        assertThat(env.walletHolds.hasHold(created.internalId())).isTrue();
        assertThat(env.walletHolds.holdIdOf(created.internalId())).isEqualTo(result.holdId());

        // event emitted with the hold ledger entry id
        var event = env.events.eventsOfType("payments.payment.pending_provider.v1").get(0);
        assertThat(event.subject()).isEqualTo(created.id());
        assertThat(event.data()).isInstanceOf(com.sharkpay.payments.events.PaymentEvents.PaymentData.class);
        var data = (com.sharkpay.payments.events.PaymentEvents.PaymentData) event.data();
        assertThat(data.entry_id()).isEqualTo(result.intent().holdEntryId());
    }

    @Test
    void replaysAreIdempotentNoOps() {
        PaymentIntent intent = env.createDefault();
        env.events.reset();

        var second = env.placeHold.place(intent.id());

        assertThat(second.skipped()).isTrue(); // already PENDING_PROVIDER
        assertThat(second.holdId()).isNull();
        assertThat(env.walletHolds.placedHolds()).hasSize(1);  // no double hold
        assertThat(env.ledger.attemptCount(intent.internalId(), LedgerPort.EntryType.HOLD))
                .isEqualTo(1);
        assertThat(env.events.events()).isEmpty();
    }

    @Test
    void blockedAndTerminalIntentsSkipToo() {
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.deny("deny"));
        PaymentIntent blocked = env.create("k-blocked");
        assertThat(env.placeHold.place(blocked.id()).skipped()).isTrue();
        assertThat(env.walletHolds.placedHolds()).isEmpty();
    }

    @Test
    void unknownPaymentsAreNotFound() {
        assertThatThrownBy(() -> env.placeHold.place("pay_0123456789abcdef0123456789abcdee"))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class);
    }

    @Test
    void anIntentWithoutAnInternalWalletDestinationFailsClosedBeforeAnyMoneyMoves() {
        // a rehydrated external-rail intent can never place a wallet hold —
        // fail closed before any ledger entry or hold, not half-way through
        PaymentIntent external = com.sharkpay.payments.domain.PaymentIntent.newIntent(
                env.randomness.paymentId(), java.util.UUID.randomUUID(),
                env.principals.principalId(), null,
                com.sharkpay.payments.domain.Destination.externalRail("msisdn:+254712345678"),
                com.sharkpay.money.Money.of(150_000, "KES"),
                com.sharkpay.money.Money.of(750, "KES"), "k-external",
                com.sharkpay.payments.domain.Rail.HONEYCOIN,
                PaymentsTestEnv.START.plusSeconds(900), java.util.Map.of(),
                PaymentsTestEnv.START);
        env.payments.save(external);

        assertThatThrownBy(() -> env.placeHold.place(external.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no internal wallet destination");
        assertThat(env.ledger.totalEffects()).isZero();
        assertThat(env.walletHolds.placedHolds()).isEmpty();
        assertThat(env.payments.findById(external.id()).orElseThrow().state())
                .isEqualTo(PaymentState.CREATED); // unchanged, retryable
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
