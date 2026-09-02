package com.sharkpay.reconciliation.api;

import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal recon-break surface (standalone MockMvc, Jackson 3 NON_NULL):
 * the console list with composable filters, break detail with live aging,
 * manual lifecycle transitions, and operator A's compensation proposal
 * with full Idempotency-Key semantics.
 */
class ReconBreaksControllerTest {

    private ReconTestEnv env;
    private MockMvc mockMvc;
    private String breakId;

    @BeforeEach
    void setUp() {
        env = new ReconTestEnv();
        mockMvc = env.mockMvc();
        env.seedProviderLine("hc_amount", "CONFIRMED", 150_000, 500);
        env.seedInternalLine("int_amount", "hc_amount", "CONFIRMED", 149_500, 500);
        breakId = env.triggerDefault("key-run").breaks().get(0).id();
    }

    @Test
    void breaksAreListedWithComposableFilters() throws Exception {
        // a second run over the NEXT day's window finds exactly one new
        // break (the first window's lines stay in the first window)
        env.clock.advance(Duration.ofHours(25));
        env.providers.seed("hc_other", "CONFIRMED", 1_000, "KES", 0,
                java.time.Instant.parse("2026-09-02T12:00:00Z"));
        env.triggerRun.trigger("key-run-2", "honeycoin",
                java.time.Instant.parse("2026-09-02T00:00:00Z"),
                java.time.Instant.parse("2026-09-03T00:00:00Z"));

        mockMvc.perform(get("/internal/recon/breaks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.breaks[0].break_type").value("amount_mismatch"));

        mockMvc.perform(get("/internal/recon/breaks").param("state", "open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(get("/internal/recon/breaks").param("aging", "aging"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.breaks[0].age_hours").value(25))
                .andExpect(jsonPath("$.breaks[0].bucket").value("aging"));

        mockMvc.perform(get("/internal/recon/breaks").param("aging", "stale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        mockMvc.perform(get("/internal/recon/breaks").param("provider", "other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        // unknown filter values are loud 400s, never guesses
        mockMvc.perform(get("/internal/recon/breaks").param("state", "closed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        mockMvc.perform(get("/internal/recon/breaks").param("aging", "ancient"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void theBreakDetailCarriesBothSidesFactsAndLiveAging() throws Exception {
        mockMvc.perform(get("/internal/recon/breaks/{id}", breakId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(breakId))
                .andExpect(jsonPath("$.break_type").value("amount_mismatch"))
                .andExpect(jsonPath("$.provider_ref").value("hc_amount"))
                .andExpect(jsonPath("$.internal_ref").value("int_amount"))
                .andExpect(jsonPath("$.provider_amount.amount_minor").value(150000))
                .andExpect(jsonPath("$.internal_amount.amount_minor").value(149500))
                .andExpect(jsonPath("$.provider_fee.amount_minor").value(500))
                .andExpect(jsonPath("$.internal_fee.amount_minor").value(500))
                .andExpect(jsonPath("$.provider_status").value("CONFIRMED"))
                .andExpect(jsonPath("$.internal_status").value("CONFIRMED"))
                .andExpect(jsonPath("$.state").value("open"))
                .andExpect(jsonPath("$.bucket").value("fresh"))
                .andExpect(jsonPath("$.detected_at", startsWith("2026-09-01T10:00:00")))
                .andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.compensation_id").doesNotExist());

        mockMvc.perform(get("/internal/recon/breaks/{id}", "brk_unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void transitionsMoveTheLifecycleAndRecordTheAuditFields() throws Exception {
        mockMvc.perform(post("/internal/recon/breaks/{id}/transitions", breakId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"investigating\",\"principal\":\"ops.alice\","
                                + "\"note\":\"timing skew hypothesis\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("investigating"))
                .andExpect(jsonPath("$.note").value("timing skew hypothesis"))
                .andExpect(jsonPath("$.last_actor").value("ops.alice"))
                .andExpect(jsonPath("$.last_transition_at").isNotEmpty());

        mockMvc.perform(post("/internal/recon/breaks/{id}/transitions", breakId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"resolved\",\"principal\":\"ops.alice\","
                                + "\"note\":\"provider re-issued the statement\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("resolved"))
                .andExpect(jsonPath("$.resolved_at").isNotEmpty());

        // a terminal break is frozen: 409 state_conflict
        mockMvc.perform(post("/internal/recon/breaks/{id}/transitions", breakId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"investigating\",\"principal\":\"ops.alice\","
                                + "\"note\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"));

        // unknown break / unknown target / blank note
        mockMvc.perform(post("/internal/recon/breaks/{id}/transitions", "brk_unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"resolved\",\"principal\":\"ops.alice\",\"note\":\"n\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/internal/recon/breaks/{id}/transitions", breakId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"closed\",\"principal\":\"ops.alice\",\"note\":\"n\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("unknown break state")));
        mockMvc.perform(post("/internal/recon/breaks/{id}/transitions", breakId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"resolved\",\"principal\":\"ops.alice\",\"note\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void compensatedIsNotAManualTransitionTarget() throws Exception {
        mockMvc.perform(post("/internal/recon/breaks/{id}/transitions", breakId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"compensated\",\"principal\":\"ops.alice\","
                                + "\"note\":\"try by hand\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"))
                .andExpect(jsonPath("$.error.message").value(containsString("4-eyes")));
    }

    @Test
    void operatorAProposesACompensationWithIdempotencySemantics() throws Exception {
        String body = proposeBody();

        MvcResult first = mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", breakId)
                        .header("Idempotency-Key", "key-prop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern("^cmp_[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.break_id").value(breakId))
                .andExpect(jsonPath("$.provider").value("honeycoin"))
                .andExpect(jsonPath("$.compensation_key").value("ops:adj:" + breakId))
                .andExpect(jsonPath("$.state").value("proposed"))
                .andExpect(jsonPath("$.requester").value("ops.alice"))
                .andExpect(jsonPath("$.approver").doesNotExist())
                .andExpect(jsonPath("$.ledger_entry_id").doesNotExist())
                .andExpect(jsonPath("$.reason").value("settlement variance"))
                .andExpect(jsonPath("$.legs.length()").value(2))
                .andExpect(jsonPath("$.legs[0].account_ref").value("suspense:recon:KES"))
                .andExpect(jsonPath("$.legs[0].direction").value("debit"))
                .andExpect(jsonPath("$.legs[0].amount.amount_minor").value(500))
                .andExpect(jsonPath("$.legs[1].account_ref").value("honeycoin:settlement:KES"))
                .andExpect(jsonPath("$.legs[1].direction").value("credit"))
                .andReturn();

        // replay: same key, same payload → the same proposal, flagged
        MvcResult replay = mockMvc.perform(
                        post("/internal/recon/breaks/{id}/compensations", breakId)
                                .header("Idempotency-Key", "key-prop")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(env.compensations.count()).isEqualTo(1);

        // different payload under the same key → 409
        mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", breakId)
                        .header("Idempotency-Key", "key-prop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody("different reason")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));

        // missing key → 400; nothing drafted
        mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", breakId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("Idempotency-Key")));
    }

    @Test
    void aProposalOnAnUnknownOrTerminalBreakIs404Or409() throws Exception {
        mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", "brk_unknown")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody()))
                .andExpect(status().isNotFound());

        env.transitionBreak.transition(breakId, "investigating", "ops.alice",
                "hypothesis: statement re-issued");
        env.transitionBreak.transition(breakId, "resolved", "ops.alice", "matched");
        mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", breakId)
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"))
                .andExpect(jsonPath("$.error.message").value(
                        containsString("terminal break is never compensated")));
    }

    @Test
    void malformedProposalsAre400s() throws Exception {
        // one leg only
        mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", breakId)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requester\":\"ops.alice\",\"reason\":\"r\",\"legs\":["
                                + "{\"account_ref\":\"suspense:recon:KES\",\"direction\":\"debit\","
                                + "\"amount_minor\":500,\"currency\":\"KES\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));

        // unbalanced legs (domain rule)
        mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", breakId)
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requester\":\"ops.alice\",\"reason\":\"r\",\"legs\":["
                                + "{\"account_ref\":\"a\",\"direction\":\"debit\","
                                + "\"amount_minor\":500,\"currency\":\"KES\"},"
                                + "{\"account_ref\":\"b\",\"direction\":\"credit\","
                                + "\"amount_minor\":100,\"currency\":\"KES\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("balance")));

        // unknown currency
        mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", breakId)
                        .header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBodyLegCurrency("ZZZ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.message").value(containsString("ZZZ")));

        // unknown direction
        mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", breakId)
                        .header("Idempotency-Key", "key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBodyLegDirection("sideways")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("posting direction")));

        assertThat(env.compensations.count()).isZero();
    }

    @Test
    void theCompensationsOfABreakAreListedInProposalOrder() throws Exception {
        proposeViaApi("key-1");
        proposeViaApi("key-2");
        proposeViaApi("key-3");

        mockMvc.perform(get("/internal/recon/breaks/{id}/compensations", breakId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.compensations[0].compensation_key").value(
                        "ops:adj:" + breakId))
                .andExpect(jsonPath("$.compensations[1].compensation_key").value(
                        "ops:adj:" + breakId + "#2"))
                .andExpect(jsonPath("$.compensations[2].compensation_key").value(
                        "ops:adj:" + breakId + "#3"));
    }

    private void proposeViaApi(String key) throws Exception {
        mockMvc.perform(post("/internal/recon/breaks/{id}/compensations", breakId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody()))
                .andExpect(status().isCreated());
    }

    private static String proposeBody() {
        return proposeBody("settlement variance");
    }

    private static String proposeBody(String reason) {
        return "{\"requester\":\"ops.alice\",\"reason\":\"" + reason + "\",\"legs\":["
                + "{\"account_ref\":\"suspense:recon:KES\",\"direction\":\"debit\","
                + "\"amount_minor\":500,\"currency\":\"KES\"},"
                + "{\"account_ref\":\"honeycoin:settlement:KES\",\"direction\":\"credit\","
                + "\"amount_minor\":500,\"currency\":\"KES\"}]}";
    }

    private static String proposeBodyLegCurrency(String currency) {
        return "{\"requester\":\"ops.alice\",\"reason\":\"r\",\"legs\":["
                + "{\"account_ref\":\"a\",\"direction\":\"debit\","
                + "\"amount_minor\":500,\"currency\":\"KES\"},"
                + "{\"account_ref\":\"b\",\"direction\":\"credit\","
                + "\"amount_minor\":500,\"currency\":\"" + currency + "\"}]}";
    }

    private static String proposeBodyLegDirection(String direction) {
        return "{\"requester\":\"ops.alice\",\"reason\":\"r\",\"legs\":["
                + "{\"account_ref\":\"a\",\"direction\":\"" + direction + "\","
                + "\"amount_minor\":500,\"currency\":\"KES\"},"
                + "{\"account_ref\":\"b\",\"direction\":\"credit\","
                + "\"amount_minor\":500,\"currency\":\"KES\"}]}";
    }
}
