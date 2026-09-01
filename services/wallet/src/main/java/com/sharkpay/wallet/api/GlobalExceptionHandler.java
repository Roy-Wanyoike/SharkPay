package com.sharkpay.wallet.api;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.money.UnknownCurrencyException;
import com.sharkpay.wallet.api.dto.ErrorEnvelope;
import com.sharkpay.wallet.domain.DuplicateWalletException;
import com.sharkpay.wallet.domain.HoldStateException;
import com.sharkpay.wallet.domain.IdempotencyConflictException;
import com.sharkpay.wallet.domain.InsufficientFundsException;
import com.sharkpay.wallet.domain.PrincipalNotActiveException;
import com.sharkpay.wallet.domain.ProjectionInconsistencyException;
import com.sharkpay.wallet.domain.UnknownPrincipalException;
import com.sharkpay.wallet.domain.WalletDomainException;
import com.sharkpay.wallet.domain.WalletStateException;
import com.sharkpay.wallet.service.Ids;
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
 * 409 state_conflict / duplicate_wallet / idempotency_conflict, 422 business
 * rejections (insufficient_funds, currency_mismatch, principal_not_active,
 * money_overflow, projection_inconsistency), 500 internal_error.
 */
@RestControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ErrorEnvelope> notFound(NoSuchElementException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(UnknownPrincipalException.class)
    ResponseEntity<ErrorEnvelope> unknownPrincipal(UnknownPrincipalException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(WalletStateException.class)
    ResponseEntity<ErrorEnvelope> walletState(WalletStateException e) {
        return envelope(HttpStatus.CONFLICT, "state_conflict", e.getMessage());
    }

    @ExceptionHandler(HoldStateException.class)
    ResponseEntity<ErrorEnvelope> holdState(HoldStateException e) {
        return envelope(HttpStatus.CONFLICT, "state_conflict", e.getMessage());
    }

    @ExceptionHandler(DuplicateWalletException.class)
    ResponseEntity<ErrorEnvelope> duplicateWallet(DuplicateWalletException e) {
        return envelope(HttpStatus.CONFLICT, "duplicate_wallet", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorEnvelope> idempotencyConflict(IdempotencyConflictException e) {
        return envelope(HttpStatus.CONFLICT, "idempotency_conflict", e.getMessage());
    }

    @ExceptionHandler(PrincipalNotActiveException.class)
    ResponseEntity<ErrorEnvelope> principalNotActive(PrincipalNotActiveException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "principal_not_active", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ErrorEnvelope> insufficientFunds(InsufficientFundsException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("available_minor", e.available().amountMinor());
        details.put("currency", e.available().currency());
        details.put("requested_minor", e.requested().amountMinor());
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient_funds", e.getMessage(), details);
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ResponseEntity<ErrorEnvelope> currencyMismatch(CurrencyMismatchException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "currency_mismatch", e.getMessage());
    }

    @ExceptionHandler(MoneyOverflowException.class)
    ResponseEntity<ErrorEnvelope> moneyOverflow(MoneyOverflowException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "money_overflow", e.getMessage());
    }

    @ExceptionHandler(ProjectionInconsistencyException.class)
    ResponseEntity<ErrorEnvelope> projectionInconsistency(ProjectionInconsistencyException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "ledger_projection_inconsistency",
                e.getMessage());
    }

    @ExceptionHandler({UnknownCurrencyException.class})
    ResponseEntity<ErrorEnvelope> unknownCurrency(UnknownCurrencyException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
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

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorEnvelope> malformedRequest(RuntimeException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    @ExceptionHandler(WalletDomainException.class)
    ResponseEntity<ErrorEnvelope> domainError(WalletDomainException e) {
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
