package com.sharkpay.fx.api;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.testsupport.FxTestEnv;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-MockMvc contract tests for {@link FxController} +
 * {@link GlobalExceptionHandler} (contracts/openapi/v1/fx.yaml and
 * common.yaml error envelope). No Spring context, per ADR 003.
 */
class FxApiTest {

    private FxTestEnv env;
    private MockMvc mockMvc;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        env = new FxTestEnv();
        mockMvc = env.mockMvc();
    }

    private String quoteBody(long amountMinor, String base, String quote, Integer ttl) {
        String ttlField = ttl == null ? "" : ",\"expires_in_seconds\":" + ttl;
        return "{\"amount_minor\":" + amountMinor
                + ",\"base_currency\":\"" + base + "\""
                + ",\"quote_currency\":\"" + quote + "\"" + ttlField + "}";
    }

    private String convertBody(String quoteId, String source, String destination) {
        return "{\"quote_id\":\"" + quoteId + "\""
                + ",\"source_wallet\":\"" + source + "\""
                + ",\"destination_wallet\":\"" + destination + "\"}";
    }

    /** Creates a QUOTED quote over HTTP and returns its id. */
    private String createQuote(String base, String quote) throws Exception {
        MvcResult result = mockMvc.perform(post("/fx/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quoteBody(10000, base, quote, 60)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json.readValue(result.getResponse().getContentAsString(), JsonNode.class);
        return body.get("id").asString();
    }

    /** Creates and locks a quote over HTTP and returns the quote id. */
    private String lockQuote(String base, String quote) throws Exception {
        String id = createQuote(base, quote);
        mockMvc.perform(post("/fx/quotes/{id}/lock", id)).andExpect(status().isOk());
        return id;
    }

    @Nested
    class CreateQuote {

        @Test
        void createsAQuoteWith201AndCanonicalMoney() throws Exception {
            mockMvc.perform(post("/fx/quotes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(quoteBody(10000, "USD", "KES", 60)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(Matchers.startsWith("fxq_")))
                    .andExpect(jsonPath("$.state").value("QUOTED"))
                    .andExpect(jsonPath("$.base_currency").value("USD"))
                    .andExpect(jsonPath("$.quote_currency").value("KES"))
                    .andExpect(jsonPath("$.source_amount.amount_minor").value(10000))
                    .andExpect(jsonPath("$.source_amount.currency").value("USD"))
                    .andExpect(jsonPath("$.source_amount.exponent").value(2))
                    .andExpect(jsonPath("$.target_amount.currency").value("KES"))
                    .andExpect(jsonPath("$.target_amount.amount_minor").value(Matchers.greaterThan(0)))
                    .andExpect(jsonPath("$.rate.value_minor").value(Matchers.greaterThan(0)))
                    .andExpect(jsonPath("$.rate.base_currency").value("USD"))
                    .andExpect(jsonPath("$.rate.quote_currency").value("KES"))
                    .andExpect(jsonPath("$.expires_at").isNotEmpty())
                    .andExpect(jsonPath("$.created_at").isNotEmpty());
        }

        @Test
        void rejectsZeroAndNegativeAmountsWith400() throws Exception {
            mockMvc.perform(post("/fx/quotes").contentType(MediaType.APPLICATION_JSON)
                            .content(quoteBody(0, "USD", "KES", 60)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));

            mockMvc.perform(post("/fx/quotes").contentType(MediaType.APPLICATION_JSON)
                            .content(quoteBody(-5, "USD", "KES", 60)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }

        @Test
        void rejectsBlankCurrenciesWith400() throws Exception {
            mockMvc.perform(post("/fx/quotes").contentType(MediaType.APPLICATION_JSON)
                            .content(quoteBody(10000, "", "KES", 60)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }

        @Test
        void rejectsTtlOutOfRangeWith400() throws Exception {
            mockMvc.perform(post("/fx/quotes").contentType(MediaType.APPLICATION_JSON)
                            .content(quoteBody(10000, "USD", "KES", 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));

            mockMvc.perform(post("/fx/quotes").contentType(MediaType.APPLICATION_JSON)
                            .content(quoteBody(10000, "USD", "KES", 3601)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }

        @Test
        void rejectsSameCurrencyWith422() throws Exception {
            mockMvc.perform(post("/fx/quotes").contentType(MediaType.APPLICATION_JSON)
                            .content(quoteBody(10000, "USD", "USD", 60)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("same_currency"));
        }

        @Test
        void rejectsUnsupportedPairWith422() throws Exception {
            mockMvc.perform(post("/fx/quotes").contentType(MediaType.APPLICATION_JSON)
                            .content(quoteBody(10000, "XXX", "KES", 60)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("unsupported_currency_pair"));
        }

        @Test
        void rejectsAKnownPairNoRateSourceServesWith422() throws Exception {
            // GBP and KES are both supported currencies, but the sandbox rate
            // table serves no GBP:KES pair — same 422 business rejection.
            mockMvc.perform(post("/fx/quotes").contentType(MediaType.APPLICATION_JSON)
                            .content(quoteBody(10000, "GBP", "KES", 60)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("unsupported_currency_pair"));
        }

        @Test
        void rejectsMalformedJsonWith400() throws Exception {
            mockMvc.perform(post("/fx/quotes").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\": \"not-a-number\""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }
    }

    @Nested
    class GetQuote {

        @Test
        void returnsTheQuoteById() throws Exception {
            String id = createQuote("USD", "KES");
            mockMvc.perform(get("/fx/quotes/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.state").value("QUOTED"));
        }

        @Test
        void unknownQuoteIs404() throws Exception {
            mockMvc.perform(get("/fx/quotes/{id}", "fxq_unknown"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
    }

    @Nested
    class LockQuote {

        @Test
        void locksAQuotedQuoteAndIsIdempotent() throws Exception {
            String id = createQuote("USD", "KES");
            mockMvc.perform(post("/fx/quotes/{id}/lock", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("LOCKED"));
            // re-locking an already-locked quote returns the same state
            mockMvc.perform(post("/fx/quotes/{id}/lock", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("LOCKED"));
        }

        @Test
        void unknownQuoteIs404() throws Exception {
            mockMvc.perform(post("/fx/quotes/{id}/lock", "fxq_unknown"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }

        @Test
        void expiredQuotedQuoteIsA409StateConflict() throws Exception {
            String id = createQuote("USD", "KES"); // TTL 60s in this test
            env.clock.advance(Duration.ofSeconds(61));
            mockMvc.perform(post("/fx/quotes/{id}/lock", id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("state_conflict"));
        }
    }

    @Nested
    class Convert {

        @Test
        void convertsALockedQuoteWith201() throws Exception {
            String quoteId = lockQuote("USD", "KES");
            MvcResult result = mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "test-key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src-USD", "wallet/dst-KES")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.state").value("EXECUTED"))
                    .andExpect(jsonPath("$.quote_id").value(quoteId))
                    .andExpect(jsonPath("$.source_wallet").value("wallet/src-USD"))
                    .andExpect(jsonPath("$.destination_wallet").value("wallet/dst-KES"))
                    .andExpect(jsonPath("$.entry_id").isNotEmpty())
                    .andExpect(jsonPath("$.source_amount.currency").value("USD"))
                    .andExpect(jsonPath("$.target_amount.currency").value("KES"))
                    .andExpect(header().doesNotExist(FxController.IDEMPOTENT_REPLAY_HEADER))
                    .andReturn();
            JsonNode body = json.readValue(result.getResponse().getContentAsString(), JsonNode.class);
            String conversionId = body.get("id").asString();

            mockMvc.perform(get("/fx/conversions/{id}", conversionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(conversionId))
                    .andExpect(jsonPath("$.state").value("EXECUTED"));
        }

        @Test
        void replaysIdempotentConvertWithHeaderAndNoDoubleEffect() throws Exception {
            String quoteId = lockQuote("USD", "KES");
            long postAttemptsBefore = env.ledger.postAttempts();

            MvcResult first = mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "replay-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src", "wallet/dst")))
                    .andExpect(status().isCreated())
                    .andReturn();
            String firstId = json.readValue(first.getResponse().getContentAsString(), JsonNode.class)
                    .get("id").asString();

            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "replay-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src", "wallet/dst")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(firstId))
                    .andExpect(header().string(FxController.IDEMPOTENT_REPLAY_HEADER, "true"));

            org.junit.jupiter.api.Assertions.assertEquals(postAttemptsBefore + 1, env.ledger.postAttempts());
            org.junit.jupiter.api.Assertions.assertEquals(1, env.events.eventsOfType("fx.conversion.executed.v1").size());
        }

        @Test
        void sameKeyDifferentBodyIsA409IdempotencyConflict() throws Exception {
            String quoteId = lockQuote("USD", "KES");
            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "conflict-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src", "wallet/dst")))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "conflict-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/OTHER", "wallet/dst")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
        }

        @Test
        void missingIdempotencyKeyIs400() throws Exception {
            String quoteId = lockQuote("USD", "KES");
            mockMvc.perform(post("/fx/convert")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src", "wallet/dst")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }

        @Test
        void blankIdempotencyKeyValueIs400() throws Exception {
            String quoteId = lockQuote("USD", "KES");
            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "   ")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src", "wallet/dst")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }

        @Test
        void unexpectedDomainFailuresMapTo500AndReleaseTheKey() throws Exception {
            String quoteId = lockQuote("USD", "KES");
            env.ledger.failNextPosting();

            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "outage-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src", "wallet/dst")))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("internal_error"))
                    .andExpect(jsonPath("$.error.request_id").isNotEmpty());

            // the failed key was released: retrying the SAME key executes
            // (no replay header) — the ledger outage was transient
            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "outage-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src", "wallet/dst")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.state").value("EXECUTED"))
                    .andExpect(header().doesNotExist(FxController.IDEMPOTENT_REPLAY_HEADER));
        }

        @Test
        void unknownQuoteIs404() throws Exception {
            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "key-404")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody("fxq_unknown", "wallet/src", "wallet/dst")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }

        @Test
        void convertingAQuotedNotLockedQuoteIsA409StateConflict() throws Exception {
            String quoteId = createQuote("USD", "KES");
            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "key-quoted")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src", "wallet/dst")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("state_conflict"));
        }

        @Test
        void blankFieldsAreRejectedWith400() throws Exception {
            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "key-blank")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody("", "wallet/src", "wallet/dst")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }
    }

    @Nested
    class GetConversion {

        @Test
        void unknownConversionIs404() throws Exception {
            mockMvc.perform(get("/fx/conversions/{id}", "cnv_unknown"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
    }

    @Nested
    class QuoteLifecycle {

        @Test
        void quoteStateAdvancesAcrossTheDocumentedLifecycle() throws Exception {
            String quoteId = createQuote("USD", "KES");
            mockMvc.perform(get("/fx/quotes/{id}", quoteId))
                    .andExpect(jsonPath("$.state").value("QUOTED"));
            mockMvc.perform(post("/fx/quotes/{id}/lock", quoteId))
                    .andExpect(jsonPath("$.state").value("LOCKED"));
            mockMvc.perform(post("/fx/convert")
                            .header(FxController.IDEMPOTENCY_HEADER, "lifecycle-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertBody(quoteId, "wallet/src", "wallet/dst")))
                    .andExpect(status().isCreated());
            mockMvc.perform(get("/fx/quotes/{id}", quoteId))
                    .andExpect(jsonPath("$.state").value("EXECUTED"));
        }
    }
}
