package com.sharkpay.payments.api;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.money.UnknownCurrencyException;
import com.sharkpay.payments.api.dto.ErrorEnvelope;
import com.sharkpay.payments.domain.IdempotencyConflictException;
import com.sharkpay.payments.domain.PaymentDomainException;
import com.sharkpay.payments.domain.PaymentStateException;
import com.sharkpay.payments.domain.ReversalExceedsCapturedException;
import com.sharkpay.payments.domain.RiskReviewException;
import com.sharkpay.payments.domain.UnsupportedCurrencyException;
import com.sharkpay.payments.domain.UnknownPaymentException;
import com.sharkpay.payments.domain.UnknownWalletException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Maps domain and validation errors to the canonical error envelope
 * (contracts/openapi/v1/common.yaml), copying the wallet service's error
 * semantics: 400 validation_error (malformed), 404 not_found, 409
 * state_conflict / idempotency_conflict, 422 business rejections
 * (unsupported_currency, risk_blocked, money_overflow, currency_mismatch,
 * reversal_exceeds_captured), 500 internal_error.
 */
@RestControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler({UnknownPaymentException.class, UnknownWalletException.class,
            NoSuchElementException.class})
    ResponseEntity<ErrorEnvelope> notFound(RuntimeException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(PaymentStateException.class)
    ResponseEntity<ErrorEnvelope> stateConflict(PaymentStateException e) {
        return envelope(HttpStatus.CONFLICT, "state_conflict", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorEnvelope> idempotencyConflict(IdempotencyConflictException e) {
        return envelope(HttpStatus.CONFLICT, "idempotency_conflict", e.getMessage());
    }

    @ExceptionHandler({UnsupportedCurrencyException.class, UnknownCurrencyException.class})
    ResponseEntity<ErrorEnvelope> unsupportedCurrency(RuntimeException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_currency", e.getMessage());
    }

    @ExceptionHandler(RiskReviewException.class)
    ResponseEntity<ErrorEnvelope> riskBlocked(RiskReviewException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reasons", e.reasons());
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "risk_blocked", e.getMessage(), details);
    }

    @ExceptionHandler(ReversalExceedsCapturedException.class)
    ResponseEntity<ErrorEnvelope> reversalExceedsCaptured(ReversalExceedsCapturedException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "reversal_exceeds_captured",
                e.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ResponseEntity<ErrorEnvelope> currencyMismatch(CurrencyMismatchException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "currency_mismatch", e.getMessage());
    }

    @ExceptionHandler(MoneyOverflowException.class)
    ResponseEntity<ErrorEnvelope> moneyOverflow(MoneyOverflowException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "money_overflow", e.getMessage());
    }

    @ExceptionHandler(InvalidAmountException.class)
    ResponseEntity<ErrorEnvelope> invalidAmount(InvalidAmountException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorEnvelope> missingHeader(MissingRequestHeaderException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("header", e.getHeaderName());
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage(), details);
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
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorEnvelope> malformedRequest(RuntimeException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    @ExceptionHandler(PaymentDomainException.class)
    ResponseEntity<ErrorEnvelope> domainError(PaymentDomainException e) {
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> unexpected(Exception e) {
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred.");
    }

    private static ResponseEntity<ErrorEnvelope> envelope(HttpStatus status, String code,
                                                          String message) {
        return envelope(status, code, message, null);
    }

    private static ResponseEntity<ErrorEnvelope> envelope(HttpStatus status, String code,
                                                          String message,
                                                          Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(new ErrorEnvelope(new ErrorEnvelope.Error(code,
                        message == null ? status.getReasonPhrase() : message,
                        com.sharkpay.payments.service.Ids.requestId(), details)));
    }
}
