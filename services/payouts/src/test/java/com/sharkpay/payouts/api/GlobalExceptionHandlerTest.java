package com.sharkpay.payouts.api;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.InvalidAmountException;
import com.sharkpay.money.Money;
import com.sharkpay.money.MoneyOverflowException;
import com.sharkpay.money.UnknownCurrencyException;
import com.sharkpay.payouts.api.dto.ErrorEnvelope;
import com.sharkpay.payouts.domain.IdempotencyConflictException;
import com.sharkpay.payouts.domain.InsufficientFundsException;
import com.sharkpay.payouts.domain.KycRequiredException;
import com.sharkpay.payouts.domain.LedgerPostingException;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.PayoutStateException;
import com.sharkpay.payouts.domain.PayoutsDomainException;
import com.sharkpay.payouts.domain.PrincipalNotActiveException;
import com.sharkpay.payouts.domain.ReturnCompensationException;
import com.sharkpay.payouts.domain.RiskDeniedException;
import com.sharkpay.payouts.domain.SameWalletException;
import com.sharkpay.payouts.domain.TransferStateException;
import com.sharkpay.payouts.domain.UnknownWalletException;
import com.sharkpay.payouts.domain.WalletFrozenException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical error envelope mapping (contracts/openapi/v1/common.yaml)
 * — every domain exception family lands on its documented status + code,
 * the request id always matches {@code ^req_[0-9A-Za-z]+$}, and internal
 * errors never leak their cause.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static final String ID = "pot_0123456789abcdef0123456789abcdef";

    @Test
    void notFoundMapsNoSuchElementAndUnknownWallet() {
        assertThat(code(handler.notFound(new NoSuchElementException("payout " + ID
                + " not found")))).isEqualTo(tuple(HttpStatus.NOT_FOUND, "not_found"));
        assertThat(code(handler.notFound(new UnknownWalletException("wal_x"))))
                .isEqualTo(tuple(HttpStatus.NOT_FOUND, "not_found"));
    }

    @Test
    void stateConflictsMapPayoutTransferAndLateRiskDeny() {
        assertThat(code(handler.stateConflict(new PayoutStateException(ID, PayoutState.SENT,
                PayoutState.CANCELLED)))).isEqualTo(tuple(HttpStatus.CONFLICT, "state_conflict"));
        assertThat(code(handler.stateConflict(new TransferStateException("trf_x",
                com.sharkpay.payouts.domain.TransferState.SUCCEEDED,
                com.sharkpay.payouts.domain.TransferState.FAILED))))
                .isEqualTo(tuple(HttpStatus.CONFLICT, "state_conflict"));
        assertThat(code(handler.stateConflict(new RiskDeniedException(ID,
                PayoutState.PROCESSING)))).isEqualTo(tuple(HttpStatus.CONFLICT, "state_conflict"));
        assertThat(code(handler.idempotencyConflict(new IdempotencyConflictException("k"))))
                .isEqualTo(tuple(HttpStatus.CONFLICT, "idempotency_conflict"));
    }

    @Test
    void businessRejectionsMapOnto422WithTheirCodes() {
        assertThat(code(handler.walletFrozen(new WalletFrozenException("wal_x"))))
                .isEqualTo(tuple(HttpStatus.UNPROCESSABLE_CONTENT, "wallet_frozen"));
        assertThat(code(handler.sameWallet(new SameWalletException("wal_x"))))
                .isEqualTo(tuple(HttpStatus.UNPROCESSABLE_CONTENT, "same_wallet"));
        assertThat(code(handler.kycRequired(new KycRequiredException(UUID.randomUUID()))))
                .isEqualTo(tuple(HttpStatus.UNPROCESSABLE_CONTENT, "kyc_required"));
        assertThat(code(handler.principalNotActive(new PrincipalNotActiveException(
                UUID.randomUUID(), "SUSPENDED"))))
                .isEqualTo(tuple(HttpStatus.UNPROCESSABLE_CONTENT, "principal_not_active"));
        assertThat(code(handler.currencyMismatch(new CurrencyMismatchException("KES", "USD"))))
                .isEqualTo(tuple(HttpStatus.UNPROCESSABLE_CONTENT, "currency_mismatch"));
        assertThat(code(handler.moneyOverflow(new MoneyOverflowException("boom"))))
                .isEqualTo(tuple(HttpStatus.UNPROCESSABLE_CONTENT, "money_overflow"));
        assertThat(code(handler.unsupportedDestination(
                new com.sharkpay.payouts.domain.UnsupportedDestinationException("bad msisdn"))))
                .isEqualTo(tuple(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_destination"));
    }

    @Test
    void insufficientFundsCarriesTheNumbersInDetails() {
        ResponseEntity<ErrorEnvelope> response = handler.insufficientFunds(
                new InsufficientFundsException(Money.of(1_000, "KES"), Money.of(5_000, "KES")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().error().code()).isEqualTo("insufficient_funds");
        assertThat(response.getBody().error().details())
                .containsEntry("available_minor", 1_000L)
                .containsEntry("currency", "KES")
                .containsEntry("requested_minor", 5_000L);
    }

    @Test
    void returnCompensationCarriesPayoutIdAndReasonInDetails() {
        ResponseEntity<ErrorEnvelope> response = handler.returnCompensation(
                ReturnCompensationException.negative(ID, Money.of(1_000, "KES"),
                        Money.of(5_500, "KES")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().error().code())
                .isEqualTo("return_compensation_impossible");
        assertThat(response.getBody().error().details())
                .containsEntry("payout_id", ID)
                .containsEntry("reason", "negative_compensation");
    }

    @Test
    void validationErrorsAre400sWithTheFieldMessage() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "amount_minor",
                "amount_minor must be a positive integer"));
        ResponseEntity<ErrorEnvelope> response = handler.invalidBody(
                new MethodArgumentNotValidException(null, binding));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("validation_error");
        assertThat(response.getBody().error().message())
                .isEqualTo("amount_minor: amount_minor must be a positive integer");

        // empty binding falls back to a generic message
        ResponseEntity<ErrorEnvelope> empty = handler.invalidBody(
                new MethodArgumentNotValidException(null, new BeanPropertyBindingResult(new
                        Object(), "request")));
        assertThat(empty.getBody().error().message()).isEqualTo("request body is invalid");
    }

    @Test
    void malformedRequestsCoverTheRemaining400Family() {
        assertThat(code(handler.unknownCurrency(new UnknownCurrencyException("nope"))))
                .isEqualTo(tuple(HttpStatus.BAD_REQUEST, "validation_error"));
        assertThat(code(handler.invalidAmount(new InvalidAmountException("bad"))))
                .isEqualTo(tuple(HttpStatus.BAD_REQUEST, "validation_error"));
        assertThat(code(handler.malformedRequest(new IllegalArgumentException("bad shape"))))
                .isEqualTo(tuple(HttpStatus.BAD_REQUEST, "validation_error"));
        assertThat(code(handler.malformedRequest(new HttpMessageNotReadableException(
                "nope", null))))
                .isEqualTo(tuple(HttpStatus.BAD_REQUEST, "validation_error"));

        ResponseEntity<ErrorEnvelope> missingHeader = handler.missingHeader(
                new MissingRequestHeaderException("Idempotency-Key", headerParameter()));
        assertThat(missingHeader.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingHeader.getBody().error().code()).isEqualTo("validation_error");
        assertThat(missingHeader.getBody().error().details())
                .containsEntry("header", "Idempotency-Key");
    }

    @Test
    void internalErrorsMaskTheCauseButKeepARequestId() {
        LedgerPostingException secret = new LedgerPostingException("payouts:pot_x:hold",
                "connection refused to 10.0.0.7 with credentials abc", null);
        ResponseEntity<ErrorEnvelope> response = handler.ledgerPosting(secret);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("internal_error");
        assertThat(response.getBody().error().message())
                .isEqualTo("An unexpected error occurred.");
        assertThat(response.getBody().error().request_id()).matches("^req_[0-9A-Za-z]+$");

        assertThat(code(handler.domainError(new PayoutsDomainException("unmapped"))))
                .isEqualTo(tuple(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error"));
        assertThat(code(handler.unexpected(new RuntimeException("boom"))))
                .isEqualTo(tuple(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error"));
        // a null message is masked with the same generic body — never leaks
        ResponseEntity<ErrorEnvelope> nullMessage = handler.unexpected(
                new RuntimeException((String) null));
        assertThat(nullMessage.getBody().error().message())
                .isEqualTo("An unexpected error occurred.");
    }

    @Test
    void everyEnvelopeCarriesARequest_idMatchingTheContractPattern() {
        for (ResponseEntity<ErrorEnvelope> response : new ResponseEntity[]{
                handler.notFound(new NoSuchElementException("x")),
                handler.stateConflict(new PayoutStateException(ID, PayoutState.SENT,
                        PayoutState.CANCELLED)),
                handler.unexpected(new RuntimeException("x"))}) {
            assertThat(response.getBody().error().request_id()).matches("^req_[0-9A-Za-z]+$");
            assertThat(response.getBody().error().code()).isNotBlank();
        }
    }

    private record Tuple(HttpStatusCode status, String code) {
    }

    private static Tuple tuple(HttpStatusCode status, String code) {
        return new Tuple(status, code);
    }

    private Tuple code(ResponseEntity<ErrorEnvelope> response) {
        return tuple(response.getStatusCode(), response.getBody().error().code());
    }

    /** The controller's idempotency-key header parameter (real, not null). */
    private static org.springframework.core.MethodParameter headerParameter() {
        try {
            return new org.springframework.core.MethodParameter(
                    TransferController.class.getDeclaredMethod("create", String.class,
                            com.sharkpay.payouts.api.dto.TransferCreateRequest.class), 0);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("TransferController.create moved?", e);
        }
    }
}
