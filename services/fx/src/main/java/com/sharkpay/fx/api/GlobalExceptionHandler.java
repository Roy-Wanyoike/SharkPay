package com.sharkpay.fx.api;

import com.sharkpay.fx.domain.FxDomainException;
import com.sharkpay.fx.domain.IdempotencyConflictException;
import com.sharkpay.fx.domain.QuoteExpiredException;
import com.sharkpay.fx.domain.QuoteStateException;
import com.sharkpay.fx.domain.SameCurrencyException;
import com.sharkpay.fx.domain.UnsupportedCurrencyPairException;
import com.sharkpay.fx.service.Ids;
import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.UnknownCurrencyException;
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
 * (contracts/openapi/v1/common.yaml): 400 validation_error, 404 not_found,
 * 409 state_conflict / idempotency_conflict, 422 business rejections,
 * 500 internal_error.
 */
@RestControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ErrorEnvelope> notFound(NoSuchElementException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(QuoteExpiredException.class)
    ResponseEntity<ErrorEnvelope> quoteExpired(QuoteExpiredException e) {
        return envelope(HttpStatus.CONFLICT, "state_conflict", e.getMessage());
    }

    @ExceptionHandler(QuoteStateException.class)
    ResponseEntity<ErrorEnvelope> quoteState(QuoteStateException e) {
        return envelope(HttpStatus.CONFLICT, "state_conflict", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorEnvelope> idempotencyConflict(IdempotencyConflictException e) {
        return envelope(HttpStatus.CONFLICT, "idempotency_conflict", e.getMessage());
    }

    @ExceptionHandler(SameCurrencyException.class)
    ResponseEntity<ErrorEnvelope> sameCurrency(SameCurrencyException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "same_currency", e.getMessage());
    }

    @ExceptionHandler(UnsupportedCurrencyPairException.class)
    ResponseEntity<ErrorEnvelope> unsupportedPair(UnsupportedCurrencyPairException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_currency_pair", e.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ResponseEntity<ErrorEnvelope> currencyMismatch(CurrencyMismatchException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "currency_mismatch", e.getMessage());
    }

    @ExceptionHandler({InvalidAmountException.class, UnknownCurrencyException.class})
    ResponseEntity<ErrorEnvelope> invalidMoney(com.sharkpay.money.MoneyException e) {
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

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorEnvelope> malformedRequest(RuntimeException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    @ExceptionHandler(FxDomainException.class)
    ResponseEntity<ErrorEnvelope> domainError(FxDomainException e) {
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> unexpected(Exception e) {
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred.");
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
