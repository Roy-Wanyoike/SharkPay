package com.sharkpay.risk.api;

import com.sharkpay.risk.domain.exceptions.CaseNotFoundException;
import com.sharkpay.risk.domain.exceptions.EvaluationConflictException;
import com.sharkpay.risk.domain.exceptions.EvaluationNotFoundException;
import com.sharkpay.risk.domain.exceptions.IllegalCaseTransitionException;
import com.sharkpay.risk.domain.exceptions.InvalidCaseIdException;
import com.sharkpay.risk.domain.exceptions.InvalidEvaluationException;
import com.sharkpay.money.MoneyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Maps domain/transport errors onto the internal error contract
 * {@code {code, message}}: 400 validation, 404 not found, 409 state or
 * idempotency conflict, 500 internal.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> onValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "request body failed validation";
        }
        return badRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> onUnreadable(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof InvalidEvaluationException invalid) {
            return badRequest(invalid.getMessage());
        }
        return badRequest("request body is not valid JSON or violates the request schema");
    }

    @ExceptionHandler({InvalidEvaluationException.class, InvalidCaseIdException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorDto> onInvalidInput(RuntimeException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MoneyException.class})
    public ResponseEntity<ErrorDto> onIllegalArgument(RuntimeException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler({EvaluationNotFoundException.class, CaseNotFoundException.class})
    public ResponseEntity<ErrorDto> onNotFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorDto.of("not_found", e.getMessage()));
    }

    @ExceptionHandler(EvaluationConflictException.class)
    public ResponseEntity<ErrorDto> onConflict(EvaluationConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorDto.of("idempotency_conflict", e.getMessage()));
    }

    @ExceptionHandler(IllegalCaseTransitionException.class)
    public ResponseEntity<ErrorDto> onIllegalTransition(IllegalCaseTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorDto.of("state_conflict", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> onUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorDto.of("internal_error", "an unexpected error occurred"));
    }

    private static ResponseEntity<ErrorDto> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorDto.of("validation_error", message));
    }
}
