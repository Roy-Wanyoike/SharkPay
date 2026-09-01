package com.sharkpay.payments.api;

import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * payments.yaml EXACTLY: createPayment (201 + X-Idempotent-Replay),
 * listPayments (cursor + filters), getPayment, cancelPayment (200 + replay,
 * 409 state_conflict). Wallet error semantics: 400 malformed, 404 unknown,
 * 409 idempotency_conflict/state_conflict, 422 business rejections.
 * Standalone MockMvc + Jackson 3 (no Spring context, ADR 003).
 */
class PaymentControllerTest {

    private PaymentsTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new PaymentsTestEnv();
        mockMvc = env.mockMvc();
    }

    private static final String BODY = """
            {"amount_minor": 150000, "currency": "KES",
             "destination_wallet": "wal_0123456789abcdef0123456789abcdef",
             "rail": "honeycoin", "metadata": {"order_id": "A-7731"},
             "expires_in_seconds": 900}
            """;

    @Test
    void createReturns201WithThePendingProviderIntentAndHeaders() throws Exception {
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-Id", startsWith("req_")))
                .andExpect(header().doesNotExist("X-Idempotent-Replay"))
                .andExpect(jsonPath("$.id", startsWith("pay_")))
                .andExpect(jsonPath("$.state").value("PENDING_PROVIDER"))
                .andExpect(jsonPath("$.amount.amount_minor").value(150000))
                .andExpect(jsonPath("$.amount.currency").value("KES"))
                .andExpect(jsonPath("$.amount.exponent").value(2))
                .andExpect(jsonPath("$.fee.amount_minor").value(750))
                .andExpect(jsonPath("$.destination_wallet").value("wal_0123456789abcdef0123456789abcdef"))
                .andExpect(jsonPath("$.rail").value("honeycoin"))
                .andExpect(jsonPath("$.metadata.order_id").value("A-7731"))
                .andExpect(jsonPath("$.next_action.type").value("none"))
                .andExpect(jsonPath("$.provider_ref").isNotEmpty())
                .andExpect(jsonPath("$.expires_at", startsWith("2026-09-01T10:15:00")))
                .andExpect(jsonPath("$.created_at", startsWith("2026-09-01T10:00:00")))
                .andExpect(jsonPath("$.updated_at", startsWith("2026-09-01T10:00:00")))
                .andExpect(jsonPath("$.failure_reason").doesNotExist());
    }

    @Test
    void createOmitsOptionalFieldsWhenAbsent() throws Exception {
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content("""
                                {"amount_minor": 1000, "currency": "KES",
                                 "destination_wallet": "wal_0123456789abcdef0123456789abcdef"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING_PROVIDER"))
                .andExpect(jsonPath("$.rail").value("honeycoin")) // default rail
                .andExpect(jsonPath("$.metadata").doesNotExist())
                .andExpect(jsonPath("$.expires_at", startsWith("2026-09-01T10:15:00")));
    }

    @Test
    void createReplayReturnsTheOriginalWithTheReplayHeader() throws Exception {
        MvcResult first = mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "k-1").contentType("application/json")
                .content(BODY)).andReturn();
        tools.jackson.databind.JsonNode body = new tools.jackson.databind.json.JsonMapper()
                .readTree(first.getResponse().getContentAsString());
        String originalId = body.get("id").asString();

        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(originalId));
        // one intent, one hold, one initiation — no second effect
        org.assertj.core.api.Assertions.assertThat(env.payments.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(env.walletHolds.placedHolds()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(env.gateway.initiations()).hasSize(1);
    }

    @Test
    void createKeyReuseWithDifferentPayloadIsA409() throws Exception {
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                .contentType("application/json").content(BODY)).andReturn();

        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY.replace("150000", "42")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"))
                .andExpect(jsonPath("$.error.request_id", startsWith("req_")));
    }

    @Test
    void createRequiresAnIdempotencyKey() throws Exception {
        mockMvc.perform(post("/payments").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void createMalformedBodiesAre400s() throws Exception {
        // missing amount
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content("""
                                {"currency": "KES", "destination_wallet": "wal_0123456789abcdef0123456789abcdef"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        // zero amount
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY.replace("150000", "0")))
                .andExpect(status().isBadRequest());
        // bad wallet pattern
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY.replace(
                                "wal_0123456789abcdef0123456789abcdef", "wallet-1")))
                .andExpect(status().isBadRequest());
        // bad rail
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY.replace("honeycoin", "paypal")))
                .andExpect(status().isBadRequest());
        // expires_in_seconds below 60
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY.replace("900", "10")))
                .andExpect(status().isBadRequest());
        // unknown field (additionalProperties: false)
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY.replaceFirst("\\{",
                                "{\"surprise\": 1, ")))
                .andExpect(status().isBadRequest());
        // not JSON at all
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBusinessRejectionsAre422s() throws Exception {
        // unknown currency → 422 unsupported_currency
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY.replace("KES", "CHF")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("unsupported_currency"));

        // risk REVIEW → 422 risk_blocked with reasons
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.review("manual review"));
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-2")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("risk_blocked"))
                .andExpect(jsonPath("$.error.details.reasons[0]").value("manual review"));
    }

    @Test
    void createUnknownDestinationWalletIsA404() throws Exception {
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY.replace(
                                "wal_0123456789abcdef0123456789abcdef",
                                "wal_0123456789abcdef0123456789abcdff")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void createRiskDenyReturns201WithTheBlockedState() throws Exception {
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.deny("velocity"));
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("BLOCKED"))
                .andExpect(jsonPath("$.provider_ref").doesNotExist());
    }

    @Test
    void createTransientProviderOutageStaysPendingProvider() throws Exception {
        env.gateway.unavailableNextInitiations(1);
        mockMvc.perform(post("/payments").header("Idempotency-Key", "k-1")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING_PROVIDER"))
                .andExpect(jsonPath("$.provider_ref").doesNotExist());
    }

    @Test
    void getReturnsTheIntentAnd404sUnknownIds() throws Exception {
        String id = env.createDefault().id();
        mockMvc.perform(get("/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.state").value("PENDING_PROVIDER"));

        mockMvc.perform(get("/payments/{id}", "pay_0123456789abcdef0123456789abcdee"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void listPaginatesFiltersAndValidates() throws Exception {
        for (int i = 0; i < 3; i++) {
            env.create("k-" + i);
        }
        env.recordResult.record(null, env.payments.findById(
                env.create("k-done").id()).orElseThrow().id(), "SUCCEEDED");

        mockMvc.perform(get("/payments").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.next_cursor").isNotEmpty());

        mockMvc.perform(get("/payments").param("state", "SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].state").value("SUCCEEDED"));

        mockMvc.perform(get("/payments").param("state", "PENDING_PROVIDER")
                        .param("principal_id", env.principals.principalId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)));

        mockMvc.perform(get("/payments").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next_cursor").doesNotExist());

        mockMvc.perform(get("/payments").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        mockMvc.perform(get("/payments").param("state", "bogus"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/payments").param("principal_id", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/payments").param("created_from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelCancelsReplaysAndConflicts() throws Exception {
        String id = env.createDefault().id();

        mockMvc.perform(post("/payments/{id}/cancel", id).header("Idempotency-Key", "ck-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", startsWith("req_")))
                .andExpect(header().doesNotExist("X-Idempotent-Replay"))
                .andExpect(jsonPath("$.state").value("CANCELLED"));

        // replay: same key + same payment → replay header, exactly one release
        mockMvc.perform(post("/payments/{id}/cancel", id).header("Idempotency-Key", "ck-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        org.assertj.core.api.Assertions.assertThat(env.ledger.totalEffects()).isEqualTo(2); // HOLD + RELEASE
        org.assertj.core.api.Assertions.assertThat(env.walletHolds.placedHolds()).hasSize(1);

        // different payment, same key → 409 idempotency_conflict
        String other = env.create("k-other").id();
        mockMvc.perform(post("/payments/{id}/cancel", other).header("Idempotency-Key", "ck-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));

        // cancelling a confirmed intent → 409 state_conflict
        String confirmed = env.create("k-confirmed").id();
        env.recordResult.record(null, confirmed, "SUCCEEDED");
        mockMvc.perform(post("/payments/{id}/cancel", confirmed)
                        .header("Idempotency-Key", "ck-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"));

        // unknown payment → 404; missing key → 400
        mockMvc.perform(post("/payments/{id}/cancel", "pay_0123456789abcdef0123456789abcdee")
                        .header("Idempotency-Key", "ck-3"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/payments/{id}/cancel", other))
                .andExpect(status().isBadRequest());
    }
}
