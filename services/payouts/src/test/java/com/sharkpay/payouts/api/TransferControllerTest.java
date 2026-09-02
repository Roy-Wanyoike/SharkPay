package com.sharkpay.payouts.api;

import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /transfers (contracts/openapi/v1/transfers.yaml) on standalone
 * MockMvc: 201 with the terminal state, X-Idempotent-Replay on replays,
 * 400/404/409/422 semantics exactly as the contract's error envelope
 * defines them, and optional fields omitted (NON_NULL).
 */
class TransferControllerTest {

    private PayoutsTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new PayoutsTestEnv();
        mockMvc = env.mockMvc();
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void aHappyTransferReturns201TerminalSucceededWithTheLedgerEntry() throws Exception {
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s",
                         "amount_minor":250000,"currency":"KES",
                         "metadata":{"reason":"invoice-settlement"}}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(
                        org.hamcrest.Matchers.matchesPattern("^trf_[0-9A-Za-z]{20,}$")))
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.source_wallet").value(PayoutsTestEnv.WALLET))
                .andExpect(jsonPath("$.destination_wallet").value(PayoutsTestEnv.OTHER_WALLET))
                .andExpect(jsonPath("$.amount.amount_minor").value(250000))
                .andExpect(jsonPath("$.amount.currency").value("KES"))
                .andExpect(jsonPath("$.amount.exponent").value(2))
                .andExpect(jsonPath("$.fee.amount_minor").value(0))
                .andExpect(jsonPath("$.entry_id").isNotEmpty())
                .andExpect(jsonPath("$.failure_reason").doesNotExist())
                .andExpect(jsonPath("$.metadata.reason").value("invoice-settlement"))
                .andExpect(jsonPath("$.created_at").value("2026-09-01T10:00:00Z"));
    }

    @Test
    void aReplayReturns201WithTheIdempotentReplayHeaderAndNoSecondPosting() throws Exception {
        String body = """
                {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1000,
                 "currency":"KES"}
                """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET);
        mockMvc.perform(create(body).header("Idempotency-Key", "replay-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));

        mockMvc.perform(create(body).header("Idempotency-Key", "replay-key"))
                .andExpect(status().isCreated())
                .andExpect(headerTrue());
        org.assertj.core.api.Assertions.assertThat(env.ledger.journal()).hasSize(1);
    }

    @Test
    void theSameKeyWithADifferentPayloadIsA409IdempotencyConflict() throws Exception {
        String first = """
                {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1000,
                 "currency":"KES"}
                """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET);
        mockMvc.perform(create(first).header("Idempotency-Key", "conflict"));
        String second = """
                {"source_wallet":"%s","destination_wallet":"%s","amount_minor":2000,
                 "currency":"KES"}
                """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET);
        mockMvc.perform(create(second).header("Idempotency-Key", "conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    void aMissingIdempotencyKeyIsA400ValidationError() throws Exception {
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1000,
                         "currency":"KES"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.message").value(
                        "Idempotency-Key header must not be blank"));
    }

    @Test
    void aBlankIdempotencyKeyIsA400ValidationError() throws Exception {
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1000,
                         "currency":"KES"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void anUnknownSourceWalletIsA404() throws Exception {
        mockMvc.perform(create("""
                        {"source_wallet":"wal_0000000000000000000000000",
                         "destination_wallet":"%s","amount_minor":1000,"currency":"KES"}
                        """.formatted(PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void aFrozenSourceWalletIsA422WalletFrozen() throws Exception {
        env.wallets.freeze(PayoutsTestEnv.WALLET);
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1000,
                         "currency":"KES"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("wallet_frozen"));
    }

    @Test
    void insufficientFundsIsA422WithTheNumbersInDetails() throws Exception {
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s",
                         "amount_minor":%d,"currency":"KES"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET,
                        PayoutsTestEnv.DEFAULT_BALANCE + 1))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("insufficient_funds"))
                .andExpect(jsonPath("$.error.details.available_minor")
                        .value(PayoutsTestEnv.DEFAULT_BALANCE))
                .andExpect(jsonPath("$.error.details.currency").value("KES"))
                .andExpect(jsonPath("$.error.details.requested_minor")
                        .value(PayoutsTestEnv.DEFAULT_BALANCE + 1));
    }

    @Test
    void theSameWalletTwiceIsA422SameWallet() throws Exception {
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1000,
                         "currency":"KES"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("same_wallet"));
    }

    @Test
    void aCurrencyMismatchAgainstTheWalletsIsA422() throws Exception {
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1000,
                         "currency":"USD"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("currency_mismatch"));
    }

    @Test
    void aLedgerRejectionReturns201TerminalFailedWithNoPartialPosting() throws Exception {
        env.ledger.rejectPrefix("transfers:");
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1000,
                         "currency":"KES"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.entry_id").doesNotExist())
                .andExpect(jsonPath("$.failure_reason").isNotEmpty());
        org.assertj.core.api.Assertions.assertThat(env.ledger.journal()).isEmpty();
    }

    @Test
    void anUnreachableLedgerIsA500InternalErrorWithNoCauseLeak() throws Exception {
        env.ledger.failPrefix("transfers:",
                new com.sharkpay.payouts.domain.LedgerPostingException("transfers:x",
                        "connection refused: secrets inside", null));
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1000,
                         "currency":"KES"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    void requestBodiesAreValidatedWith400s() throws Exception {
        // malformed wallet pattern
        mockMvc.perform(create("""
                        {"source_wallet":"wallet-1","destination_wallet":"%s","amount_minor":1000,
                         "currency":"KES"}
                        """.formatted(PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.message").value(
                        "source_wallet: source_wallet must match ^wal_[0-9A-Za-z]{20,}$"));
        // zero amount
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":0,
                         "currency":"KES"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        "amount_minor: amount_minor must be a positive integer"));
        // unknown currency
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1,
                         "currency":"XYZ"}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        // missing required field
        mockMvc.perform(create("""
                        {"source_wallet":"%s","amount_minor":1,"currency":"KES"}
                        """.formatted(PayoutsTestEnv.WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest());
        // malformed JSON
        mockMvc.perform(create("{not json").header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        // unknown fields are rejected (FAIL_ON_UNKNOWN_PROPERTIES)
        mockMvc.perform(create("""
                        {"source_wallet":"%s","destination_wallet":"%s","amount_minor":1,
                         "currency":"KES","extra":true}
                        """.formatted(PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest());
        // missing body
        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.ResultMatcher headerTrue() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("X-Idempotent-Replay", "true");
    }
}
