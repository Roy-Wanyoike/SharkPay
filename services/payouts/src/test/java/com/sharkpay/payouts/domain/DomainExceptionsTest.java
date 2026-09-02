package com.sharkpay.payouts.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StateTransition audit rows and every domain exception's message/accessor
 * contract (docs/DATA-MODEL.md §1: "every mutable state change also writes
 * a transition/audit row"). The exceptions back the canonical error
 * envelope mapping — their codes and payloads are API contract.
 */
class DomainExceptionsTest {

    private static final String ID = "pot_0123456789abcdef0123456789abcdef";
    private static final String TID = "trf_0123456789abcdef0123456789abcdef";

    @Test
    void stateTransitionsRecordTriggerActorAndOccurrence() {
        Instant at = Instant.parse("2026-09-01T10:00:00Z");
        StateTransition transition = new StateTransition(PayoutState.PENDING_RISK,
                PayoutState.PROCESSING, "scheduler", "scheduler", "submitted", at);
        assertThat(transition.from()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(transition.to()).isEqualTo(PayoutState.PROCESSING);
        assertThat(transition.fromWire()).isEqualTo("PENDING_RISK");
        assertThat(transition.toWire()).isEqualTo("PROCESSING");
        assertThat(transition.occurredAt()).isEqualTo(at);

        StateTransition transferTransition = new StateTransition(TransferState.CREATED,
                TransferState.SUCCEEDED, "ledger_confirmation", "system", null, at);
        assertThat(transferTransition.fromWire()).isEqualTo("CREATED");
        assertThat(transferTransition.toWire()).isEqualTo("SUCCEEDED");

        // non-enum states render via toString (defensive branch)
        assertThat(new StateTransition("X", 1, "t", "a", null, at).fromWire()).isEqualTo("X");
    }

    @Test
    void stateTransitionsValidateTheirOwnShape() {
        Instant at = Instant.now();
        assertThatThrownBy(() -> new StateTransition(null, PayoutState.CREATED, "t", "a", null, at))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StateTransition(PayoutState.CREATED, null, "t", "a", null, at))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StateTransition(PayoutState.CREATED, PayoutState.BLOCKED,
                " ", "a", null, at))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trigger must not be blank");
        assertThatThrownBy(() -> new StateTransition(PayoutState.CREATED, PayoutState.BLOCKED,
                "risk", null, null, at))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StateTransition(PayoutState.CREATED, PayoutState.BLOCKED,
                "risk", " ", null, at))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StateTransition(PayoutState.CREATED, PayoutState.BLOCKED,
                "risk", "risk", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void payoutStateExceptionCarriesTheConflictTriple() {
        PayoutStateException exception = new PayoutStateException(ID, PayoutState.SENT,
                PayoutState.CANCELLED);
        assertThat(exception.payoutId()).isEqualTo(ID);
        assertThat(exception.from()).isEqualTo(PayoutState.SENT);
        assertThat(exception.attempted()).isEqualTo(PayoutState.CANCELLED);
        assertThat(exception.getMessage()).contains(ID).contains("SENT").contains("CANCELLED");
        assertThat(exception).isInstanceOf(PayoutsDomainException.class);
    }

    @Test
    void transferStateExceptionCarriesTheConflictTriple() {
        TransferStateException exception = new TransferStateException(TID, TransferState.SUCCEEDED,
                TransferState.FAILED);
        assertThat(exception.transferId()).isEqualTo(TID);
        assertThat(exception.from()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(exception.attempted()).isEqualTo(TransferState.FAILED);
        assertThat(exception.getMessage()).contains(TID);
    }

    @Test
    void idempotencyConflictCarriesTheKey() {
        IdempotencyConflictException exception = new IdempotencyConflictException("key-7");
        assertThat(exception.idempotencyKey()).isEqualTo("key-7");
        assertThat(exception.getMessage()).contains("Idempotency-Key");
    }

    @Test
    void moneyExceptionsCarryAmountsAndCodes() {
        InsufficientFundsException insufficient = new InsufficientFundsException(
                Money.of(1_000, "KES"), Money.of(5_000, "KES"));
        assertThat(insufficient.available()).isEqualTo(Money.of(1_000, "KES"));
        assertThat(insufficient.requested()).isEqualTo(Money.of(5_000, "KES"));
        assertThat(insufficient.getMessage()).contains("1000 KES").contains("5000 KES");

        SameWalletException sameWallet = new SameWalletException("wal_x");
        assertThat(sameWallet.walletId()).isEqualTo("wal_x");
        assertThat(sameWallet.getMessage()).contains("wal_x");

        WalletFrozenException frozen = new WalletFrozenException("wal_y");
        assertThat(frozen.walletId()).isEqualTo("wal_y");
        assertThat(frozen.getMessage()).contains("frozen");

        UnknownWalletException unknown = new UnknownWalletException("wal_z");
        assertThat(unknown.walletId()).isEqualTo("wal_z");
        assertThat(unknown.getMessage()).contains("not found");
    }

    @Test
    void principalExceptionsCarryThePrincipal() {
        UUID principalId = UUID.randomUUID();
        KycRequiredException kyc = new KycRequiredException(principalId);
        assertThat(kyc.principalId()).isEqualTo(principalId);
        assertThat(kyc.getMessage()).contains("LIMITED");

        PrincipalNotActiveException inactive = new PrincipalNotActiveException(principalId,
                "SUSPENDED");
        assertThat(inactive.principalId()).isEqualTo(principalId);
        assertThat(inactive.status()).isEqualTo("SUSPENDED");
        assertThat(inactive.getMessage()).contains("SUSPENDED");
    }

    @Test
    void returnCompensationExceptionsCarryTheReasonEnvelope() {
        ReturnCompensationException negative = ReturnCompensationException.negative(ID,
                Money.of(1_000, "KES"), Money.of(5_500, "KES"));
        assertThat(negative.reason()).isEqualTo(ReturnCompensationException.Reason
                .NEGATIVE_COMPENSATION);
        assertThat(negative.payoutId()).isEqualTo(ID);
        assertThat(negative.getMessage()).contains("ops case required");

        ReturnCompensationException mismatch = ReturnCompensationException.currencyMismatch(ID,
                "KES", "USD");
        assertThat(mismatch.reason()).isEqualTo(ReturnCompensationException.Reason
                .CURRENCY_MISMATCH);
        assertThat(mismatch.getMessage()).contains("USD").contains("KES");

        ReturnCompensationException notReturnable = ReturnCompensationException.notReturnable(ID,
                PayoutState.CREATED);
        assertThat(notReturnable.reason()).isEqualTo(ReturnCompensationException.Reason
                .NOT_RETURNABLE);
        assertThat(notReturnable.getMessage()).contains("CREATED");
    }

    @Test
    void riskDeniedExplainsTheArrivedTooLateCase() {
        RiskDeniedException exception = new RiskDeniedException(ID, PayoutState.PROCESSING);
        assertThat(exception.getMessage()).contains(ID).contains("PROCESSING")
                .contains("PENDING_RISK");
    }

    @Test
    void ledgerPostingExceptionCarriesTheTransactionKey() {
        LedgerPostingException exception = new LedgerPostingException("payouts:pot_x:hold",
                "connection refused", new RuntimeException("boom"));
        assertThat(exception.getMessage()).contains("payouts:pot_x:hold")
                .contains("connection refused");
        assertThat(exception.getCause()).hasMessageContaining("boom");
    }

    @Test
    void unsupportedDestinationCarriesItsMessage() {
        UnsupportedDestinationException exception = new UnsupportedDestinationException(
                "msisdn must match E.164 pattern");
        assertThat(exception.getMessage()).contains("E.164");
    }

    @Test
    void theBaseDomainExceptionAcceptsAMessageAndCause() {
        PayoutsDomainException withCause = new PayoutsDomainException("boom",
                new IllegalStateException("root"));
        assertThat(withCause.getMessage()).isEqualTo("boom");
        assertThat(withCause.getCause()).hasMessage("root");
        assertThat(new PayoutsDomainException("plain").getCause()).isNull();
    }
}
