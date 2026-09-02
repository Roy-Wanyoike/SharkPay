package com.sharkpay.reconciliation.api;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.money.UnknownCurrencyException;
import com.sharkpay.reconciliation.api.dto.ErrorEnvelope;
import com.sharkpay.reconciliation.domain.CompensationRejectedException;
import com.sharkpay.reconciliation.domain.FourEyesException;
import com.sharkpay.reconciliation.domain.InvalidWindowException;
import com.sharkpay.reconciliation.domain.IdempotencyConflictException;
import com.sharkpay.reconciliation.domain.ReconciliationException;
import com.sharkpay.reconciliation.domain.ReconciliationStateException;
import com.sharkpay.reconciliation.domain.StatementUnavailableException;
import com.sharkpay.reconciliation.service.Ids;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Maps domain and validation errors to the canonical error envelope
 * (contracts/openapi/v1/common.yaml), wallet-consistent semantics:
 * 400 validation_error, 404 not_found, 409 state_conflict /
 * idempotency_conflict, 422 business rejections (four_eyes_violation,
 * compensation_rejected, currency_mismatch, money_overflow), 500
 * internal_error (no cause leak).
 */
@RestControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ErrorEnvelope> notFound(NoSuchElementException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(ReconciliationStateException.class)
    ResponseEntity<ErrorEnvelope> stateConflict(ReconciliationStateException e) {
        return envelope(HttpStatus.CONFLICT, "state_conflict", e.getMessage());
    }

    @ExceptionHandler(InvalidWindowException.class)
    ResponseEntity<ErrorEnvelope> invalidWindow(InvalidWindowException e) {
        // documented on the exception itself: a malformed window is a
        // validation error, never an internal error (a from >= to window
        // previously fell through to the ReconciliationException catch-all
        // and surfaced as 500)
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorEnvelope> idempotencyConflict(IdempotencyConflictException e) {
        return envelope(HttpStatus.CONFLICT, "idempotency_conflict", e.getMessage());
    }

    @ExceptionHandler(FourEyesException.class)
    ResponseEntity<ErrorEnvelope> fourEyes(FourEyesException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "four_eyes_violation", e.getMessage());
    }

    @ExceptionHandler(CompensationRejectedException.class)
    ResponseEntity<ErrorEnvelope> compensationRejected(CompensationRejectedException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "compensation_rejected", e.getMessage(),
                e.details());
    }

    @ExceptionHandler(StatementUnavailableException.class)
    ResponseEntity<ErrorEnvelope> statementUnavailable(StatementUnavailableException e) {
        // expected upstream failure: the run is persisted FAILED with the
        // reason; this mapping only guards non-run callers
        return envelope(HttpStatus.SERVICE_UNAVAILABLE, "statement_unavailable", e.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ResponseEntity<ErrorEnvelope> currencyMismatch(CurrencyMismatchException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "currency_mismatch", e.getMessage());
    }

    @ExceptionHandler(MoneyOverflowException.class)
    ResponseEntity<ErrorEnvelope> moneyOverflow(MoneyOverflowException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "money_overflow", e.getMessage());
    }

    @ExceptionHandler(UnknownCurrencyException.class)
    ResponseEntity<ErrorEnvelope> unknownCurrency(UnknownCurrencyException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    @ExceptionHandler(InvalidAmountException.class)
    ResponseEntity<ErrorEnvelope> invalidAmount(InvalidAmountException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorEnvelope> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("request body is invalid");
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorEnvelope> malformedRequest(RuntimeException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    /**
     * Missing required query parameter (a 400, never a 500). Declared
     * separately from {@link #malformedRequest} because
     * {@code MissingServletRequestParameterException} is a
     * {@code ServletException}, not a {@code RuntimeException}: a shared
     * handler parameter of type {@code RuntimeException} could never receive
     * it (Spring would fail argument resolution at runtime and surface a 500
     * where the contract requires a 400).
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorEnvelope> missingParameter(MissingServletRequestParameterException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    @ExceptionHandler(ReconciliationException.class)
    ResponseEntity<ErrorEnvelope> domainError(ReconciliationException e) {
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> unexpected(Exception e) {
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred.");
    }

    private static ResponseEntity<ErrorEnvelope> envelope(HttpStatus status, String code, String message) {
        return envelope(status, code, message, null);
    }

    private static ResponseEntity<ErrorEnvelope> envelope(HttpStatus status, String code, String message,
                                                          Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(new ErrorEnvelope(new ErrorEnvelope.Error(code,
                        message == null ? status.getReasonPhrase() : message, Ids.requestId(), details)));
    }
}
