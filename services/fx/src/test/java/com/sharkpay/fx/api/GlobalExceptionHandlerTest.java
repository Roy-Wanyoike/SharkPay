package com.sharkpay.fx.api;

import com.sharkpay.fx.domain.FxDomainException;
import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.UnknownCurrencyException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full domain-&#8594;HTTP mapping table of the error advice, complementing
 * the MockMvc contract tests (which drive the reachable codes end to end):
 * every documented status/code pair of contracts/openapi/v1/fx.yaml +
 * common.yaml is produced with the canonical envelope shape
 * (code, message, request_id) — mirrors the wallet service's handler
 * coverage.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void moneyCurrencyMismatchIsA422BusinessRejection() {
        ResponseEntity<ErrorEnvelope> response =
                handler.currencyMismatch(new CurrencyMismatchException("USD", "KES"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().error().code()).isEqualTo("currency_mismatch");
        assertThat(response.getBody().error().message()).contains("USD");
    }

    @Test
    void invalidMoneyInputsAre400ValidationErrors() {
        ResponseEntity<ErrorEnvelope> amount =
                handler.invalidMoney(new InvalidAmountException("amount_minor must be positive"));
        assertThat(amount.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(amount.getBody().error().code()).isEqualTo("validation_error");
        assertThat(amount.getBody().error().message()).contains("amount_minor");

        ResponseEntity<ErrorEnvelope> currency = handler.invalidMoney(new UnknownCurrencyException("XYZ"));
        assertThat(currency.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(currency.getBody().error().code()).isEqualTo("validation_error");
    }

    @Test
    void unexpectedDomainAndRuntimeFailuresAreOpaque500s() {
        ResponseEntity<ErrorEnvelope> domain = handler.domainError(new FxDomainException("boom"));
        assertThat(domain.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(domain.getBody().error().code()).isEqualTo("internal_error");
        assertThat(domain.getBody().error().message()).isEqualTo("An unexpected error occurred.");

        ResponseEntity<ErrorEnvelope> unexpected = handler.unexpected(new IllegalStateException("secret"));
        assertThat(unexpected.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unexpected.getBody().error().code()).isEqualTo("internal_error");
        assertThat(unexpected.getBody().error().message()).isEqualTo("An unexpected error occurred.");
    }

    @Test
    void notFoundKeepsTheResourceMessage() {
        ResponseEntity<ErrorEnvelope> response = handler.notFound(
                new NoSuchElementException("quote fxq_x not found"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error().code()).isEqualTo("not_found");
        assertThat(response.getBody().error().message()).isEqualTo("quote fxq_x not found");
    }

    @Test
    void everyEnvelopeCarriesARequestIdAndAContractShapedCode() {
        ResponseEntity<ErrorEnvelope> response = handler.notFound(
                new NoSuchElementException("quote fxq_x not found"));
        assertThat(response.getBody().error().request_id()).startsWith("req_");
        assertThat(response.getBody().error().code()).matches("^[a-z][a-z0-9_]*$");
        assertThat(response.getBody().error().details()).isNull();
    }
}
