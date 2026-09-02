package com.sharkpay.payouts.api;

import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public payout endpoints (contracts/openapi/v1/payouts.yaml) on
 * standalone MockMvc: create (201, PENDING_RISK on acceptance / FAILED on
 * early ledger rejection, X-Idempotent-Replay on replays), get (200/404),
 * cancel (200/409/404 + replay header) — 400/404/409/422 semantics exactly
 * as the contract error envelope defines them.
 */
class PayoutControllerTest {

    private PayoutsTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new PayoutsTestEnv();
        mockMvc = env.mockMvc();
    }

    private static String createBody(String destination) {
        return """
                {"source_wallet":"%s","amount_minor":500000,"currency":"KES",
                 "destination":%s,"metadata":{"invoice":"INV-991"}}
                """.formatted(PayoutsTestEnv.WALLET, destination);
    }

    private static final String MPESA = """
            {"type":"mpesa","msisdn":"+254712345678"}""";

    @Test
    void createPayoutReturns201PendingRiskWithTheQuotedFee() throws Exception {
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA))
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(
                        org.hamcrest.Matchers.matchesPattern("^pot_[0-9A-Za-z]{20,}$")))
                .andExpect(jsonPath("$.state").value("PENDING_RISK"))
                .andExpect(jsonPath("$.source_wallet").value(PayoutsTestEnv.WALLET))
                .andExpect(jsonPath("$.amount.amount_minor").value(500000))
                .andExpect(jsonPath("$.amount.currency").value("KES"))
                .andExpect(jsonPath("$.fee.amount_minor").value(10500))
                .andExpect(jsonPath("$.destination.type").value("mpesa"))
                .andExpect(jsonPath("$.destination.msisdn").value("+254712345678"))
                .andExpect(jsonPath("$.rail").value("mpesa"))
                .andExpect(jsonPath("$.metadata.invoice").value("INV-991"))
                .andExpect(jsonPath("$.expires_at").value("2026-09-01T10:15:00Z"))
                .andExpect(jsonPath("$.created_at").value("2026-09-01T10:00:00Z"))
                .andExpect(jsonPath("$.updated_at").value("2026-09-01T10:00:00Z"))
                .andExpect(jsonPath("$.failure_reason").doesNotExist())
                .andExpect(jsonPath("$.return_reason").doesNotExist())
                .andExpect(jsonPath("$.provider_ref").doesNotExist());
    }

    @Test
    void createPayoutReplayReturns201WithTheReplayHeaderAndNoSecondHold() throws Exception {
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA)).header("Idempotency-Key", "replay-key"))
                .andExpect(status().isCreated());
        int journalBefore = env.ledger.journal().size();

        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA)).header("Idempotency-Key", "replay-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING_RISK"))
                .andExpect(header().string("X-Idempotent-Replay", "true"));

        org.assertj.core.api.Assertions.assertThat(env.ledger.journal())
                .hasSize(journalBefore);
    }

    @Test
    void createPayoutConflictIsA409() throws Exception {
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(MPESA)).header("Idempotency-Key", "conflict"));
        String different = createBody("""
                {"type":"bank","bank_code":"KCB","account_number":"ACC-1"}""");
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(different).header("Idempotency-Key", "conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
    }

    @Test
    void anOnChainPayoutRoutesAndQuotesInUsdc() throws Exception {
        String wallet = "wal_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        env.registerWallet(wallet, env.principalId, "USDC", 30_000_000, java.util.UUID.randomUUID());
        String body = """
                {"source_wallet":"%s","amount_minor":25000000,"currency":"USDC",
                 "destination":{"type":"on_chain","network":"base",
                 "address":"0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d"}}
                """.formatted(wallet);
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("Idempotency-Key", "k"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING_RISK"))
                .andExpect(jsonPath("$.fee.amount_minor").value(312500))
                .andExpect(jsonPath("$.fee.currency").value("USDC"))
                .andExpect(jsonPath("$.amount.exponent").value(6))
                .andExpect(jsonPath("$.rail").value("on_chain"))
                .andExpect(jsonPath("$.destination.network").value("base"));
    }

    @Test
    void anEarlyLedgerRejectionReturns201TerminalFailed() throws Exception {
        env.ledger.rejectPrefix("payouts:");
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA)).header("Idempotency-Key", "k"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.failure_reason").isNotEmpty())
                .andExpect(jsonPath("$.expires_at").isNotEmpty()) // optional field, still carried
                .andExpect(jsonPath("$.provider_ref").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(env.ledger.journal()).isEmpty();
    }

    @Test
    void createPayoutValidationsMapOntoTheErrorEnvelope() throws Exception {
        // unknown wallet → 404
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source_wallet":"wal_0000000000000000000000000",
                                 "amount_minor":1000,"currency":"KES",
                                 "destination":{"type":"mpesa","msisdn":"+254712345678"}}
                                """)
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        // frozen wallet → 422 wallet_frozen
        env.wallets.freeze(PayoutsTestEnv.WALLET);
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA)).header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("wallet_frozen"));
        env.wallets.addWallet(PayoutsTestEnv.WALLET, env.principalId, "KES",
                PayoutsTestEnv.DEFAULT_BALANCE, env.walletAccount);
        // rail/currency mismatch → 422 unsupported_destination
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source_wallet":"%s","amount_minor":1000,"currency":"KES",
                                 "destination":{"type":"on_chain","network":"base",
                                 "address":"0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d"}}
                                """.formatted(PayoutsTestEnv.WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("unsupported_destination"));
        // malformed msisdn → 422 unsupported_destination
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("""
                                {"type":"mpesa","msisdn":"12345"}"""))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("unsupported_destination"));
        // unknown destination type → 422 (clean payload: the domain rejects the type)
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("""
                                {"type":"paypal"}"""))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("unsupported_destination"));
        // currency mismatch vs wallet → 422
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA).replace("\"currency\":\"KES\"",
                                "\"currency\":\"USD\""))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("currency_mismatch"));
        // insufficient funds incl. fee → 422 with numbers
        // (9,999,999 + fee 105,499 = 5,500 flat + floor(9,999,999 * 1%) = 99,999)
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA).replace("\"amount_minor\":500000",
                                "\"amount_minor\":9999999"))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("insufficient_funds"))
                .andExpect(jsonPath("$.error.details.requested_minor").value(10_105_498));
        // kyc_required / principal_not_active
        env.principals.add(env.principalId,
                com.sharkpay.payouts.ports.PrincipalLookup.PrincipalStatus.ACTIVE,
                com.sharkpay.payouts.ports.PrincipalLookup.KycTier.UNVERIFIED);
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA)).header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("kyc_required"));
        env.principals.add(env.principalId,
                com.sharkpay.payouts.ports.PrincipalLookup.PrincipalStatus.CLOSED,
                com.sharkpay.payouts.ports.PrincipalLookup.KycTier.FULL);
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA)).header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("principal_not_active"));
        // overflow → 422 money_overflow
        env.principals.add(env.principalId,
                com.sharkpay.payouts.ports.PrincipalLookup.PrincipalStatus.ACTIVE,
                com.sharkpay.payouts.ports.PrincipalLookup.KycTier.LIMITED);
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA).replace("\"amount_minor\":500000",
                                "\"amount_minor\":9223372036854775807"))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("money_overflow"));
        // every rejection above posted nothing; the re-enabled principal's
        // next create is the one accepted payout (its hold is the only entry)
        org.assertj.core.api.Assertions.assertThat(env.ledger.journal()).isEmpty();
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA)).header("Idempotency-Key", "k-ok"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING_RISK"));
        org.assertj.core.api.Assertions.assertThat(env.ledger.journal()).hasSize(1);
    }

    @Test
    void createPayoutBodyValidationsAre400s() throws Exception {
        // bad wallet pattern
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA).replace(PayoutsTestEnv.WALLET, "wallet-1"))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.message").value(
                        "source_wallet: source_wallet must match ^wal_[0-9A-Za-z]{20,}$"));
        // zero amount
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA).replace("\"amount_minor\":500000",
                                "\"amount_minor\":0"))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest());
        // ttl below the floor (bean validation)
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA).replace("}}",
                                "},\"expires_in_seconds\":59}"))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        "expires_in_seconds: expires_in_seconds must be at least 60"));
        // ttl above the ceiling
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA).replace("}}",
                                "},\"expires_in_seconds\":86401}"))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest());
        // missing destination → 400
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source_wallet":"%s","amount_minor":1000,"currency":"KES"}
                                """.formatted(PayoutsTestEnv.WALLET))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest());
        // missing key → 400
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        "Idempotency-Key header must not be blank"));
        // unknown rail hint → 400
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(MPESA).replace("}}", "},\"rail\":\"pesa\"}"))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        // an unknown destination member (oneOf + additionalProperties: false) → 400
        mockMvc.perform(post("/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("""
                                {"type":"mpesa","msisdn":"+254712345678","email":"x@y.z"}"""))
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void getPayoutReturnsTheStateAnd404sForUnknownIds() throws Exception {
        var payout = env.createDefaultPayout();

        mockMvc.perform(get("/payouts/{id}", payout.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payout.id()))
                .andExpect(jsonPath("$.state").value("PENDING_RISK"))
                .andExpect(jsonPath("$.fee.amount_minor").value(10500))
                .andExpect(jsonPath("$.expires_at").isNotEmpty());

        mockMvc.perform(get("/payouts/{id}", "pot_0000000000000000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void getPayoutReflectsTheFullLifecycleStates() throws Exception {
        var payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();

        mockMvc.perform(get("/payouts/{id}", payout.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PROCESSING"))
                .andExpect(jsonPath("$.provider_ref").isNotEmpty());

        env.providerResults.ingest(payout.id(),
                com.sharkpay.payouts.ports.ProviderGatewayPort.ProviderStatus.SUCCEEDED, null,
                null, null, null, null);
        mockMvc.perform(get("/payouts/{id}", payout.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));
    }

    @Test
    void cancelPayoutReleasesTheHoldAndReturnsCancelled() throws Exception {
        var payout = env.createDefaultPayout();

        mockMvc.perform(post("/payouts/{id}/cancel", payout.id())
                        .header("Idempotency-Key", "cancel-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.failure_reason").doesNotExist());

        // replay
        mockMvc.perform(post("/payouts/{id}/cancel", payout.id())
                        .header("Idempotency-Key", "cancel-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"));
        // the hold reversal posted exactly once
        org.assertj.core.api.Assertions.assertThat(
                env.ledger.effectCount("payouts:" + payout.id() + ":release")).isEqualTo(1);
    }

    @Test
    void cancelPayoutPastPendingRiskIsA409() throws Exception {
        var payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue(); // PROCESSING

        mockMvc.perform(post("/payouts/{id}/cancel", payout.id())
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"))
                .andExpect(jsonPath("$.error.message").value(
                        "payout " + payout.id() + " is in state PROCESSING and cannot "
                                + "transition to CANCELLED"));
    }

    @Test
    void cancelPayoutValidations() throws Exception {
        mockMvc.perform(post("/payouts/{id}/cancel", "pot_0000000000000000000000000")
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        var payout = env.createDefaultPayout();
        mockMvc.perform(post("/payouts/{id}/cancel", payout.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }
}
