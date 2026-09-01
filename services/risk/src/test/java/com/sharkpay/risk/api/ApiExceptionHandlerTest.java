package com.sharkpay.risk.api;

import com.sharkpay.money.MoneyException;
import com.sharkpay.risk.domain.CaseStatus;
import com.sharkpay.risk.domain.exceptions.CaseNotFoundException;
import com.sharkpay.risk.domain.exceptions.EvaluationConflictException;
import com.sharkpay.risk.domain.exceptions.EvaluationNotFoundException;
import com.sharkpay.risk.domain.exceptions.IllegalCaseTransitionException;
import com.sharkpay.risk.domain.exceptions.InvalidCaseIdException;
import com.sharkpay.risk.domain.exceptions.InvalidEvaluationException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @SuppressWarnings("unused")
    record SampleForm(String type) {
    }

    static class SampleController {
        void sample(@jakarta.validation.constraints.NotNull String type) {
        }
    }

    private static MethodArgumentNotValidException validationFailure(String field, String message)
            throws NoSuchMethodException {
        Method method = SampleController.class.getDeclaredMethod("sample", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new SampleForm(null), "request");
        bindingResult.addError(new FieldError("request", field, message));
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }

    @Test
    void methodValidationMapsTo400WithFieldMessages() throws Exception {
        ResponseEntity<ErrorDto> response = handler.onValidation(validationFailure("kyc_tier", "must not be blank"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("validation_error");
        assertThat(response.getBody().message()).contains("kyc_tier").contains("must not be blank");
    }

    @Test
    void methodValidationWithoutFieldErrorsFallsBackToAGenericMessage() throws Exception {
        Method method = SampleController.class.getDeclaredMethod("sample", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new SampleForm("x"), "request");

        ResponseEntity<ErrorDto> response =
                handler.onValidation(new MethodArgumentNotValidException(parameter, bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("validation_error");
        assertThat(response.getBody().message()).isEqualTo("request body failed validation");
    }

    @Test
    void unreadableBodyWithADomainCauseKeepsTheDomainMessage() {
        HttpMessageNotReadableException unreadable = new HttpMessageNotReadableException(
                "bad body", new InvalidEvaluationException("amount_minor must be a positive integer, got 0"),
                (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<ErrorDto> response = handler.onUnreadable(unreadable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("validation_error");
        assertThat(response.getBody().message()).contains("amount_minor");
    }

    @Test
    void unreadableBodyWithoutADomainCauseIsAGeneric400() {
        ResponseEntity<ErrorDto> response = handler.onUnreadable(
                new HttpMessageNotReadableException("bad json", (org.springframework.http.HttpInputMessage) null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("validation_error");
        assertThat(response.getBody().message()).contains("not valid JSON");
    }

    @Test
    void invalidInputExceptionsMapTo400() {
        ResponseEntity<ErrorDto> invalid = handler.onInvalidInput(
                new InvalidEvaluationException("principal_type must not be null"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().code()).isEqualTo("validation_error");
        assertThat(invalid.getBody().message()).contains("principal_type");

        ResponseEntity<ErrorDto> badCaseId = handler.onInvalidInput(
                new InvalidCaseIdException("garbage"));
        assertThat(badCaseId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badCaseId.getBody().message()).contains("garbage");
    }

    @Test
    void illegalArgumentsAndMoneyExceptionsMapTo400() {
        ResponseEntity<ErrorDto> illegal = handler.onIllegalArgument(
                new IllegalArgumentException("channel must be one of [...]"));
        assertThat(illegal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(illegal.getBody().code()).isEqualTo("validation_error");
        assertThat(illegal.getBody().message()).contains("channel");

        ResponseEntity<ErrorDto> money = handler.onIllegalArgument(
                new MoneyException("currency mismatch: KES vs USD"));
        assertThat(money.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(money.getBody().message()).contains("currency mismatch");
    }

    @Test
    void notFoundExceptionsMapTo404() {
        ResponseEntity<ErrorDto> evaluation = handler.onNotFound(
                new EvaluationNotFoundException("id-1"));
        assertThat(evaluation.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(evaluation.getBody().code()).isEqualTo("not_found");
        assertThat(evaluation.getBody().message()).contains("id-1");

        ResponseEntity<ErrorDto> caseNotFound = handler.onNotFound(new CaseNotFoundException("case_x"));
        assertThat(caseNotFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(caseNotFound.getBody().code()).isEqualTo("not_found");
    }

    @Test
    void evaluationConflictMapsTo409WithTheIdempotencyCode() {
        ResponseEntity<ErrorDto> response = handler.onConflict(
                new EvaluationConflictException("id-42"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("idempotency_conflict");
        assertThat(response.getBody().message()).contains("id-42");
    }

    @Test
    void illegalTransitionMapsTo409WithTheStateCode() {
        IllegalCaseTransitionException exception = new IllegalCaseTransitionException(
                "case_abc", CaseStatus.OPEN, CaseStatus.CLOSED);

        ResponseEntity<ErrorDto> response = handler.onIllegalTransition(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("state_conflict");
        assertThat(response.getBody().message()).contains("open").contains("closed");
        assertThat(exception.from()).isEqualTo(CaseStatus.OPEN);
        assertThat(exception.attempted()).isEqualTo(CaseStatus.CLOSED);
        assertThat(exception.caseId()).isEqualTo("case_abc");
    }

    @Test
    void unexpectedExceptionsMapTo500WithoutLeakingDetails() {
        ResponseEntity<ErrorDto> response = handler.onUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("internal_error");
        assertThat(response.getBody().message()).isEqualTo("an unexpected error occurred");
        assertThat(response.getBody().message()).doesNotContain("boom");
    }
}
