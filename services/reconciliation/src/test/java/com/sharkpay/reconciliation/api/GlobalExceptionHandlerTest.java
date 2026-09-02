package com.sharkpay.reconciliation.api;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.money.UnknownCurrencyException;
import com.sharkpay.reconciliation.api.dto.ErrorEnvelope;
import com.sharkpay.reconciliation.domain.CompensationRejectedException;
import com.sharkpay.reconciliation.domain.FourEyesException;
import com.sharkpay.reconciliation.domain.IdempotencyConflictException;
import com.sharkpay.reconciliation.domain.InvalidWindowException;
import com.sharkpay.reconciliation.domain.ReconciliationException;
import com.sharkpay.reconciliation.domain.ReconciliationStateException;
import com.sharkpay.reconciliation.domain.StatementUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical error mapping, exercised directly (the transport-level
 * paths are covered by the controller tests): 404 not_found, 409
 * state_conflict / idempotency_conflict, 422 four_eyes_violation /
 * compensation_rejected (with details) / currency_mismatch / money_overflow,
 * 400 validation_error (incl. the window rule), 503
 * statement_unavailable, 500 internal_error with no cause leak.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundCarriesTheDomainMessage() {
        ResponseEntity<ErrorEnvelope> response = handler.notFound(
                new java.util.NoSuchElementException("recon break brk_x not found"));
        assertEnvelope(response, HttpStatus.NOT_FOUND, "not_found", "recon break brk_x not found");
    }

    @Test
    void stateConflictsAre409() {
        assertEnvelope(handler.stateConflict(
                        new ReconciliationStateException("break is already resolved")),
                HttpStatus.CONFLICT, "state_conflict", "break is already resolved");
    }

    @Test
    void idempotencyConflictsAre409() {
        assertEnvelope(handler.idempotencyConflict(new IdempotencyConflictException("key-9")),
                HttpStatus.CONFLICT, "idempotency_conflict", "key-9");
    }

    @Test
    void fourEyesViolationsAre422() {
        assertEnvelope(handler.fourEyes(new FourEyesException("ops.alice")),
                HttpStatus.UNPROCESSABLE_CONTENT, "four_eyes_violation", "ops.alice");
    }

    @Test
    void compensationRejectionsAre422WithLedgerDetails() {
        ResponseEntity<ErrorEnvelope> response = handler.compensationRejected(
                new CompensationRejectedException("insufficient_funds", "wallet below zero"));
        assertEnvelope(response, HttpStatus.UNPROCESSABLE_CONTENT, "compensation_rejected",
                "insufficient_funds");
        assertThat(response.getBody().error().details())
                .containsEntry("ledger_code", "insufficient_funds")
                .containsEntry("ledger_reason", "wallet below zero");
        assertThat(response.getBody().error().request_id()).startsWith("req_");
    }

    @Test
    void aMalformedWindowIsA400NotA500() {
        // regression: InvalidWindowException is a ReconciliationException —
        // without a dedicated handler it fell into the 500 catch-all while
        // its own contract says 400 validation_error
        assertEnvelope(handler.invalidWindow(
                        new InvalidWindowException("window from must be strictly before to")),
                HttpStatus.BAD_REQUEST, "validation_error", "strictly before");
    }

    @Test
    void statementUnavailabilityIsA503() {
        assertEnvelope(handler.statementUnavailable(
                        new StatementUnavailableException("provider statement", "honeycoin",
                                new IllegalStateException("breaker open"))),
                HttpStatus.SERVICE_UNAVAILABLE, "statement_unavailable", "breaker open");
    }

    @Test
    void moneyLibraryExceptionsMapToTheirContractedCodes() {
        assertEnvelope(handler.currencyMismatch(new CurrencyMismatchException("KES", "USD")),
                HttpStatus.UNPROCESSABLE_CONTENT, "currency_mismatch", null);
        assertEnvelope(handler.moneyOverflow(new MoneyOverflowException("overflow")),
                HttpStatus.UNPROCESSABLE_CONTENT, "money_overflow", "overflow");
        assertEnvelope(handler.unknownCurrency(new UnknownCurrencyException("XYZ")),
                HttpStatus.BAD_REQUEST, "validation_error", "XYZ");
        assertEnvelope(handler.invalidAmount(new InvalidAmountException("negative")),
                HttpStatus.BAD_REQUEST, "validation_error", "negative");
    }

    @Test
    void malformedRequestsAre400s() {
        assertEnvelope(handler.malformedRequest(new IllegalArgumentException("provider blank")),
                HttpStatus.BAD_REQUEST, "validation_error", "provider blank");
        assertEnvelope(handler.malformedRequest(new HttpMessageNotReadableException("bad json",
                        null, null)),
                HttpStatus.BAD_REQUEST, "validation_error", null);
    }

    @Test
    void aMissingQueryParameterIsA400NotA500() {
        // the handler is declared with its own (non-RuntimeException) parameter
        // type: MissingServletRequestParameterException is a ServletException,
        // so a shared RuntimeException parameter could never receive it at
        // runtime — this call proves the handler can
        assertEnvelope(handler.missingParameter(new MissingServletRequestParameterException(
                        "provider", "String")),
                HttpStatus.BAD_REQUEST, "validation_error", "provider");
    }

    @Test
    void unexpectedDomainErrorsAre500sWithNoCauseLeak() {
        ResponseEntity<ErrorEnvelope> response = handler.domainError(
                new ReconciliationStateException("boom"));
        assertEnvelope(response, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred.");

        assertEnvelope(handler.unexpected(new RuntimeException("ledger password is hunter2")),
                HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred.");
    }

    @Test
    void everyEnvelopeCarriesAFreshRequestId() {
        ResponseEntity<ErrorEnvelope> first = handler.notFound(
                new java.util.NoSuchElementException("x"));
        ResponseEntity<ErrorEnvelope> second = handler.notFound(
                new java.util.NoSuchElementException("x"));
        assertThat(first.getBody().error().request_id()).isNotBlank();
        assertThat(second.getBody().error().request_id())
                .isNotEqualTo(first.getBody().error().request_id());
    }

    private static void assertEnvelope(ResponseEntity<ErrorEnvelope> response, HttpStatus status,
                                       String code, String messagePart) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        ErrorEnvelope.Error error = response.getBody().error();
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.request_id()).startsWith("req_");
        if (messagePart != null) {
            assertThat(error.message()).contains(messagePart);
        }
        assertThat(error.message()).doesNotContain("\n");
    }
}
