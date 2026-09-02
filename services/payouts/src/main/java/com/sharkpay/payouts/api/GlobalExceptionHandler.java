package com.sharkpay.payouts.api;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.money.UnknownCurrencyException;
import com.sharkpay.payouts.api.dto.ErrorEnvelope;
import com.sharkpay.payouts.domain.IdempotencyConflictException;
import com.sharkpay.payouts.domain.InsufficientFundsException;
import com.sharkpay.payouts.domain.KycRequiredException;
import com.sharkpay.payouts.domain.LedgerPostingException;
import com.sharkpay.payouts.domain.PayoutStateException;
import com.sharkpay.payouts.domain.PayoutsDomainException;
import com.sharkpay.payouts.domain.PrincipalNotActiveException;
import com.sharkpay.payouts.domain.ReturnCompensationException;
import com.sharkpay.payouts.domain.RiskDeniedException;
import com.sharkpay.payouts.domain.SameWalletException;
import com.sharkpay.payouts.domain.TransferStateException;
import com.sharkpay.payouts.domain.UnknownWalletException;
import com.sharkpay.payouts.domain.WalletFrozenException;
import com.sharkpay.payouts.service.Ids;
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
 * 409 state_conflict / idempotency_conflict, 422 business rejections
 * (insufficient_funds, wallet_frozen, currency_mismatch, same_wallet,
 * unsupported_destination, kyc_required, principal_not_active,
 * money_overflow, return_compensation_impossible), 500 internal_error
 * (ledger port failures — safe to retry with the same Idempotency-Key, the
 * reservation is released).
 */
@RestControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler({NoSuchElementException.class, UnknownWalletException.class})
    ResponseEntity<ErrorEnvelope> notFound(RuntimeException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler({PayoutStateException.class, TransferStateException.class,
            RiskDeniedException.class})
    ResponseEntity<ErrorEnvelope> stateConflict(RuntimeException e) {
        return envelope(HttpStatus.CONFLICT, "state_conflict", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorEnvelope> idempotencyConflict(IdempotencyConflictException e) {
        return envelope(HttpStatus.CONFLICT, "idempotency_conflict", e.getMessage());
    }

    @ExceptionHandler(WalletFrozenException.class)
    ResponseEntity<ErrorEnvelope> walletFrozen(WalletFrozenException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "wallet_frozen", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ErrorEnvelope> insufficientFunds(InsufficientFundsException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("available_minor", e.available().amountMinor());
        details.put("currency", e.available().currency());
        details.put("requested_minor", e.requested().amountMinor());
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient_funds", e.getMessage(),
                details);
    }

    @ExceptionHandler(SameWalletException.class)
    ResponseEntity<ErrorEnvelope> sameWallet(SameWalletException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "same_wallet", e.getMessage());
    }

    @ExceptionHandler(com.sharkpay.payouts.domain.UnsupportedDestinationException.class)
    ResponseEntity<ErrorEnvelope> unsupportedDestination(
            com.sharkpay.payouts.domain.UnsupportedDestinationException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_destination", e.getMessage());
    }

    @ExceptionHandler(KycRequiredException.class)
    ResponseEntity<ErrorEnvelope> kycRequired(KycRequiredException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "kyc_required", e.getMessage());
    }

    @ExceptionHandler(PrincipalNotActiveException.class)
    ResponseEntity<ErrorEnvelope> principalNotActive(PrincipalNotActiveException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "principal_not_active", e.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ResponseEntity<ErrorEnvelope> currencyMismatch(CurrencyMismatchException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "currency_mismatch", e.getMessage());
    }

    @ExceptionHandler(MoneyOverflowException.class)
    ResponseEntity<ErrorEnvelope> moneyOverflow(MoneyOverflowException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "money_overflow", e.getMessage());
    }

    @ExceptionHandler(ReturnCompensationException.class)
    ResponseEntity<ErrorEnvelope> returnCompensation(ReturnCompensationException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("payout_id", e.payoutId());
        details.put("reason", e.reason().name().toLowerCase());
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "return_compensation_impossible",
                e.getMessage(), details);
    }

    @ExceptionHandler(LedgerPostingException.class)
    ResponseEntity<ErrorEnvelope> ledgerPosting(LedgerPostingException e) {
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred.");
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

    @ExceptionHandler(PayoutsDomainException.class)
    ResponseEntity<ErrorEnvelope> domainError(PayoutsDomainException e) {
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
                        message == null ? status.getReasonPhrase() : message, Ids.requestId(),
                        details)));
    }
}
