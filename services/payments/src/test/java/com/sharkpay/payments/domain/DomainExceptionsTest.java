package com.sharkpay.payments.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The domain exception taxonomy: every subclass is a PaymentDomainException
 * (the base itself maps to 500 internal_error) and carries its context for
 * the error envelope and audit logs.
 */
class DomainExceptionsTest {

    @Test
    void everyDomainExceptionExtendsTheBase() {
        assertThat(new PaymentStateException("pay_x", PaymentState.CREATED,
                PaymentState.SUCCEEDED)).isInstanceOf(PaymentDomainException.class);
        assertThat(new IdempotencyConflictException("k")).isInstanceOf(PaymentDomainException.class);
        assertThat(new ReversalExceedsCapturedException("pay_x"))
                .isInstanceOf(PaymentDomainException.class);
        assertThat(new RiskReviewException(List.of("velocity")))
                .isInstanceOf(PaymentDomainException.class);
        assertThat(new UnknownPaymentException("pay_x")).isInstanceOf(PaymentDomainException.class);
        assertThat(new UnknownWalletException("wal_x")).isInstanceOf(PaymentDomainException.class);
        assertThat(new UnsupportedCurrencyException("XYZ")).isInstanceOf(PaymentDomainException.class);
    }

    @Test
    void stateConflictCarriesFromAndAttemptedTo() {
        PaymentStateException e = new PaymentStateException("pay_0123456789abcdef0123456789abcdef",
                PaymentState.SUCCEEDED, PaymentState.FAILED);
        assertThat(e.paymentId()).isEqualTo("pay_0123456789abcdef0123456789abcdef");
        assertThat(e.from()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(e.attemptedTo()).isEqualTo(PaymentState.FAILED);
        assertThat(e.getMessage()).contains("SUCCEEDED").contains("FAILED");
    }

    @Test
    void idempotencyConflictCarriesTheKey() {
        IdempotencyConflictException e = new IdempotencyConflictException("key-42");
        assertThat(e.idempotencyKey()).isEqualTo("key-42");
        assertThat(e.getMessage()).contains("different request payload");
    }

    @Test
    void reversalGuardCarriesThePayment() {
        ReversalExceedsCapturedException e = new ReversalExceedsCapturedException("pay_1");
        assertThat(e.paymentId()).isEqualTo("pay_1");
        assertThat(e.getMessage()).contains("exceeds the captured amount");
    }

    @Test
    void riskReviewCarriesImmutableReasons() {
        RiskReviewException e = new RiskReviewException(List.of("velocity", "geo"));
        assertThat(e.reasons()).containsExactly("velocity", "geo");
        assertThat(e.getMessage()).contains("risk review").contains("velocity");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> e.reasons().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unknownEntityMessagesNameTheEntity() {
        assertThat(new UnknownPaymentException("pay_x").paymentId()).isEqualTo("pay_x");
        assertThat(new UnknownPaymentException("pay_x").getMessage()).contains("pay_x");
        assertThat(new UnknownWalletException("wal_x").walletId()).isEqualTo("wal_x");
        assertThat(new UnknownWalletException("wal_x").getMessage()).contains("wal_x");
    }

    @Test
    void unsupportedCurrencyCarriesCurrencyAndRail() {
        UnsupportedCurrencyException bare = new UnsupportedCurrencyException("XYZ");
        assertThat(bare.currency()).isEqualTo("XYZ");
        assertThat(bare.rail()).isNull();
        assertThat(bare.getMessage()).contains("XYZ").doesNotContain("rail");

        UnsupportedCurrencyException railed = new UnsupportedCurrencyException("XYZ",
                Rail.MPESA);
        assertThat(railed.rail()).isEqualTo(Rail.MPESA);
        assertThat(railed.getMessage()).contains("rail mpesa");
    }

    @Test
    void baseAcceptsACauseForWrapping() {
        PaymentDomainException wrapped = new PaymentDomainException("boom",
                new IllegalStateException("root"));
        assertThat(wrapped.getCause()).isInstanceOf(IllegalStateException.class);
    }
}
