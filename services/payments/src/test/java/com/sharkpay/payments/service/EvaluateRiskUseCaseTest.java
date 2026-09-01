package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.ports.RiskPort;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pre-authorization risk gate activity: DENY/REVIEW fail closed into BLOCKED
 * with no money moved; already-advanced intents are never re-evaluated.
 */
class EvaluateRiskUseCaseTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void denyMarksBlockedWithoutAnyMoneyMovement() {
        PaymentIntent intent = createCreated();
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.deny("velocity"));

        var result = env.evaluateRisk.evaluate(intent.id());

        assertThat(result.intent().state()).isEqualTo(PaymentState.BLOCKED);
        assertThat(result.decision().decision()).isEqualTo(RiskPort.Decision.DENY);
        assertThat(result.skipped()).isFalse();
        assertThat(env.walletHolds.placedHolds()).isEmpty();
        assertThat(env.ledger.totalEffects()).isZero();
        assertThat(env.events.events()).isEmpty(); // no catalog type for BLOCKED
    }

    @Test
    void reviewAlsoFailsClosedIntoBlocked() {
        PaymentIntent intent = createCreated();
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.review("manual"));

        var result = env.evaluateRisk.evaluate(intent.id());

        assertThat(result.intent().state()).isEqualTo(PaymentState.BLOCKED);
        assertThat(result.decision().decision()).isEqualTo(RiskPort.Decision.REVIEW);
    }

    @Test
    void allowLeavesTheIntentInCreated() {
        PaymentIntent intent = createCreated();
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.allow(1));

        var result = env.evaluateRisk.evaluate(intent.id());

        assertThat(result.intent().state()).isEqualTo(PaymentState.CREATED);
        assertThat(result.decision().tierRank()).isEqualTo(1);
        assertThat(result.skipped()).isFalse();
    }

    @Test
    void intentsPastCreatedAreNotReEvaluated() {
        PaymentIntent intent = env.createDefault(); // prefix already advanced it
        env.events.reset();

        var result = env.evaluateRisk.evaluate(intent.id());

        assertThat(result.skipped()).isTrue();
        assertThat(result.decision()).isNull();
        assertThat(result.intent().state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        // only the prefix's evaluation ran — the workflow replay adds none
        assertThat(env.risk.evaluations()).hasSize(1);
        assertThat(env.events.events()).isEmpty();
    }

    @Test
    void unknownPaymentsAreNotFound() {
        assertThatThrownBy(() -> env.evaluateRisk.evaluate("pay_0123456789abcdef0123456789abcdee"))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class);
        assertThatThrownBy(() -> env.evaluateRisk.evaluate(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** Builds an intent that stops right after creation (risk pending). */
    private PaymentIntent createCreated() {
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
