package com.sharkpay.payments.api;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.payments.api.dto.ErrorEnvelope;
import com.sharkpay.payments.domain.PaymentDomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full error-mapping table of {@link GlobalExceptionHandler} (common.yaml
 * ErrorEnvelope): every domain/validation failure maps to its documented
 * status + code, the envelope always carries a request_id, and internal
 * errors NEVER leak their cause (wallet error semantics).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void moneyLibraryFailuresMapToTheirBusinessCodes() {
        ResponseEntity<ErrorEnvelope> mismatch = handler.currencyMismatch(
                new CurrencyMismatchException("KES", "USD"));
        assertEnvelope(mismatch, HttpStatus.UNPROCESSABLE_CONTENT, "currency_mismatch");

        ResponseEntity<ErrorEnvelope> overflow = handler.moneyOverflow(
                new MoneyOverflowException("add overflow"));
        assertEnvelope(overflow, HttpStatus.UNPROCESSABLE_CONTENT, "money_overflow");

        ResponseEntity<ErrorEnvelope> invalid = handler.invalidAmount(
                new InvalidAmountException("abc"));
        assertEnvelope(invalid, HttpStatus.BAD_REQUEST, "validation_error");
    }

    @Test
    void missingHeadersCarryTheHeaderNameInTheDetails() {
        // a real MethodParameter (the controller's idempotency-key header
        // parameter) — the exception's message renders from it
        org.springframework.core.MethodParameter headerParameter;
        try {
            headerParameter = new org.springframework.core.MethodParameter(
                    PaymentController.class.getDeclaredMethod("create", String.class,
                            com.sharkpay.payments.api.dto.PaymentCreateRequest.class), 0);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("PaymentController.create moved?", e);
        }
        ResponseEntity<ErrorEnvelope> response = handler.missingHeader(
                new MissingRequestHeaderException("Idempotency-Key", headerParameter));
        assertEnvelope(response, HttpStatus.BAD_REQUEST, "validation_error");
        assertThat(response.getBody().error().message())
                .contains("Idempotency-Key").contains("not present");
        assertThat(response.getBody().error().details())
                .containsEntry("header", "Idempotency-Key");
    }

    @Test
    void unexpectedDomainAndRuntimeFailuresAre500sWithoutCauseLeakage() {
        ResponseEntity<ErrorEnvelope> domain = handler.domainError(
                new PaymentDomainException("internal detail: db pool exhausted"));
        assertEnvelope(domain, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        assertThat(domain.getBody().error().message())
                .as("the 500 message must be generic — no internal detail leaks")
                .doesNotContain("db pool").isEqualTo("An unexpected error occurred.");

        ResponseEntity<ErrorEnvelope> unexpected = handler.unexpected(
                new RuntimeException("boom: NPE at com.sharkpay.internal.Secret"));
        assertEnvelope(unexpected, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        assertThat(unexpected.getBody().error().message())
                .doesNotContain("Secret")
                .isEqualTo("An unexpected error occurred.");
    }

    @Test
    void aNullMessageFallsBackToTheStatusReasonPhrase() {
        // NoSuchElementException() carries a null message: the envelope must
        // still render a human-readable line, not "null"
        ResponseEntity<ErrorEnvelope> response = handler.notFound(new NoSuchElementException());
        assertEnvelope(response, HttpStatus.NOT_FOUND, "not_found");
        assertThat(response.getBody().error().message()).isEqualTo("Not Found");
    }

    private static void assertEnvelope(ResponseEntity<ErrorEnvelope> response, HttpStatus status,
                                       String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        ErrorEnvelope.Error error = response.getBody().error();
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.message()).isNotBlank();
        assertThat(error.request_id()).startsWith("req_");
    }
}
