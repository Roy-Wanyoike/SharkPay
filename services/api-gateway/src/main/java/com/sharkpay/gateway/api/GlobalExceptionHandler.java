package com.sharkpay.gateway.api;

import com.sharkpay.gateway.api.dto.ErrorEnvelope;
import com.sharkpay.gateway.domain.ApiKeyStateException;
import com.sharkpay.gateway.domain.DeliveryNotReplayableException;
import com.sharkpay.gateway.domain.GatewayDomainException;
import com.sharkpay.gateway.domain.HttpsUrlRequiredException;
import com.sharkpay.gateway.domain.IdempotencyConflictException;
import com.sharkpay.gateway.domain.InvalidEventTypesException;
import com.sharkpay.gateway.domain.QuotaExceededException;
import com.sharkpay.gateway.domain.SubscriptionStateException;
import com.sharkpay.gateway.domain.UnknownEventTypeException;
import com.sharkpay.gateway.domain.UnknownScopeException;
import com.sharkpay.gateway.service.Ids;
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
 * (http_url_required, invalid_events, unknown_event_type), 429
 * quota_exceeded, 500 internal_error (no cause leakage).
 */
@RestControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ErrorEnvelope> notFound(NoSuchElementException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
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
            UnknownScopeException.class})
    ResponseEntity<ErrorEnvelope> malformedRequest(RuntimeException e) {
        return envelope(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorEnvelope> idempotencyConflict(IdempotencyConflictException e) {
        return envelope(HttpStatus.CONFLICT, "idempotency_conflict", e.getMessage());
    }

    @ExceptionHandler({ApiKeyStateException.class, SubscriptionStateException.class,
            DeliveryNotReplayableException.class})
    ResponseEntity<ErrorEnvelope> stateConflict(GatewayDomainException e) {
        return envelope(HttpStatus.CONFLICT, "state_conflict", e.getMessage());
    }

    @ExceptionHandler(HttpsUrlRequiredException.class)
    ResponseEntity<ErrorEnvelope> httpsRequired(HttpsUrlRequiredException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "http_url_required", e.getMessage());
    }

    @ExceptionHandler(InvalidEventTypesException.class)
    ResponseEntity<ErrorEnvelope> invalidEvents(InvalidEventTypesException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_events", e.getMessage());
    }

    @ExceptionHandler(UnknownEventTypeException.class)
    ResponseEntity<ErrorEnvelope> unknownEventType(UnknownEventTypeException e) {
        return envelope(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_event_type", e.getMessage());
    }

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ErrorEnvelope> quotaExceeded(QuotaExceededException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("retry_after_seconds", e.retryAfterSeconds());
        details.put("window", e.monthly() ? "monthly" : "minute");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                .body(new ErrorEnvelope(new ErrorEnvelope.Error("quota_exceeded", e.getMessage(),
                        Ids.requestId(), details)));
    }

    @ExceptionHandler(GatewayDomainException.class)
    ResponseEntity<ErrorEnvelope> domainError(GatewayDomainException e) {
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
