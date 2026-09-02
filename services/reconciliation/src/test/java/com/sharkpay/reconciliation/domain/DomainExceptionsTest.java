package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.money.UnknownCurrencyException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The domain exception contracts: each subclass carries the exact HTTP
 * semantics documented on it (409 / 409 / 422 / 422 / 503 / 400) and the
 * money-library exceptions surface through the same hierarchy discipline
 * (mapped in GlobalExceptionHandlerTest).
 */
class DomainExceptionsTest {

    @Test
    void everyDomainExceptionIsAReconciliationException() {
        assertThat(new ReconciliationStateException("bad transition"))
                .isInstanceOf(ReconciliationException.class);
        assertThat(new IdempotencyConflictException("key-1"))
                .isInstanceOf(ReconciliationException.class);
        assertThat(new FourEyesException("ops.alice"))
                .isInstanceOf(ReconciliationException.class);
        assertThat(new CompensationRejectedException("unbalanced_entry", "off by 5"))
                .isInstanceOf(ReconciliationException.class);
        assertThat(new StatementUnavailableException("provider statement", "honeycoin",
                new IllegalStateException("breaker open")))
                .isInstanceOf(ReconciliationException.class);
        assertThat(new InvalidWindowException("from must be before to"))
                .isInstanceOf(ReconciliationException.class);
    }

    @Test
    void idempotencyConflictNamesTheKey() {
        IdempotencyConflictException e = new IdempotencyConflictException("key-42");
        assertThat(e.getMessage())
                .contains("key-42")
                .contains("already used with a different request");
    }

    @Test
    void fourEyesNamesTheViolatingPrincipal() {
        FourEyesException e = new FourEyesException("ops.alice");
        assertThat(e.getMessage())
                .contains("ops.alice")
                .contains("distinct persons");
    }

    @Test
    void compensationRejectionCarriesTheLedgerCodeAndReasonAsDetails() {
        CompensationRejectedException e =
                new CompensationRejectedException("insufficient_funds", "wallet below zero");
        assertThat(e.getMessage())
                .contains("insufficient_funds")
                .contains("wallet below zero");
        assertThat(e.ledgerCode()).isEqualTo("insufficient_funds");
        assertThat(e.ledgerReason()).isEqualTo("wallet below zero");
        assertThat(e.details())
                .containsEntry("ledger_code", "insufficient_funds")
                .containsEntry("ledger_reason", "wallet below zero")
                .hasSize(2);
    }

    @Test
    void statementUnavailableCarriesTheSideAndProvider() {
        StatementUnavailableException e = new StatementUnavailableException("ledger statement",
                "honeycoin", new IllegalStateException("dial tcp: refused"));
        assertThat(e.getMessage())
                .contains("ledger statement")
                .contains("honeycoin")
                .contains("dial tcp: refused");
        assertThat(e.getCause()).hasMessage("dial tcp: refused");
    }

    @Test
    void theMoneyLibraryExceptionsRemainDistinctFromTheDomainHierarchy() {
        // they are mapped by the API layer, not wrapped — the distinction is
        // the contract (422 money rejections vs 400 validation)
        assertThat(new CurrencyMismatchException("KES", "USD"))
                .isNotInstanceOf(ReconciliationException.class);
        assertThat(new MoneyOverflowException("overflow"))
                .isNotInstanceOf(ReconciliationException.class);
        assertThat(new UnknownCurrencyException("XYZ"))
                .isNotInstanceOf(ReconciliationException.class);
        assertThat(new InvalidAmountException("negative"))
                .isNotInstanceOf(ReconciliationException.class);
    }
}
