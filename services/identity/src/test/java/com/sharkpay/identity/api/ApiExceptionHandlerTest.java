package com.sharkpay.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.domain.exception.ValidationException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import com.sharkpay.identity.api.dto.ErrorResponse;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void validationExceptionMapsTo400WithCode() {
        ResponseEntity<ErrorResponse> response =
                handler.onValidation(new ValidationException("INVALID_SHARK_ID", "bad id"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_SHARK_ID");
        assertThat(response.getBody().message()).isEqualTo("bad id");
    }

    @Test
    void notFoundExceptionMapsTo404WithCode() {
        ResponseEntity<ErrorResponse> response =
                handler.onNotFound(new NotFoundException("PRINCIPAL_NOT_FOUND", "no principal"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("PRINCIPAL_NOT_FOUND");
    }

    @Test
    void conflictExceptionMapsTo409WithCode() {
        ResponseEntity<ErrorResponse> response =
                handler.onConflict(new ConflictException("IDEMPOTENCY_KEY_CONFLICT", "different body"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("different body");
    }

    @Test
    void methodArgumentNotValidMapsTo400WithFieldMessage() throws Exception {
        Method method = SampleController.class.getDeclaredMethod("sample", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new SampleForm(null), "request");
        bindingResult.addError(new FieldError("request", "type", "must not be null"));

        ResponseEntity<ErrorResponse> response =
                handler.onMethodArgumentNotValid(new MethodArgumentNotValidException(parameter, bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message()).contains("type").contains("must not be null");
    }

    @Test
    void methodArgumentNotValidWithoutFieldErrorsStillMapsTo400() throws Exception {
        Method method = SampleController.class.getDeclaredMethod("sample", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new SampleForm("x"), "request");

        ResponseEntity<ErrorResponse> response =
                handler.onMethodArgumentNotValid(new MethodArgumentNotValidException(parameter, bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message()).isEqualTo("request validation failed");
    }

    @Test
    void unreadableBodyMapsTo400() {
        ResponseEntity<ErrorResponse> response =
                handler.onUnreadable(new HttpMessageNotReadableException("bad json", (org.springframework.http.HttpInputMessage) null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("MALFORMED_BODY");
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingDetails() {
        ResponseEntity<ErrorResponse> response = handler.onUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("unexpected server error");
    }

    @SuppressWarnings("unused")
    record SampleForm(String type) {
    }

    static class SampleController {
        void sample(@jakarta.validation.constraints.NotNull String type) {
        }
    }
}
