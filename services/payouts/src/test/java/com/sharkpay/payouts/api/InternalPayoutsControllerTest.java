package com.sharkpay.payouts.api;

import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The internal service-to-service surface: provider-result ingestion
 * (idempotent on the return reference, X-Idempotent-Replay on replays),
 * risk-decision intake and the scheduler tick trigger (ops + integration
 * testing).
 */
class InternalPayoutsControllerTest {

    private PayoutsTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new PayoutsTestEnv();
        mockMvc = env.mockMvc();
    }

    /** Creates a payout and pushes it to PROCESSING. */
    private String processing() {
        var payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        return payout.id();
    }

    @Test
    void providerResultWalksThePayoutToSent() throws Exception {
        String id = processing();
        mockMvc.perform(post("/internal/payouts/{id}/provider-result", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\",\"reason\":\"rail accepted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.state").value("SENT"));
    }

    @Test
    void providerResultSettlesThroughSentToSucceeded() throws Exception {
        String id = processing();
        mockMvc.perform(post("/internal/payouts/{id}/provider-result", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));
        org.assertj.core.api.Assertions.assertThat(
                env.ledger.effectCount("payouts:" + id + ":settle")).isEqualTo(1);
    }

    @Test
    void providerResultReplayCarriesTheHeaderAndAppliesNothingNew() throws Exception {
        String id = processing();
        // returns are only legal once the rail accepted (SENT)
        mockMvc.perform(post("/internal/payouts/{id}/provider-result", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SENT"));
        String body = "{\"status\":\"RETURNED\",\"reason\":\"msisdn_not_registered\","
                + "\"returned_amount_minor\":500000,\"returned_currency\":\"KES\","
                + "\"provider_return_ref\":\"ret-77\"}";
        mockMvc.perform(post("/internal/payouts/{id}/provider-result", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RETURNED"));

        mockMvc.perform(post("/internal/payouts/{id}/provider-result", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RETURNED"))
                .andExpect(header().string("X-Idempotent-Replay", "true"));
        org.assertj.core.api.Assertions.assertThat(
                env.ledger.effectCount("payouts:" + id + ":return")).isEqualTo(1);
    }

    @Test
    void providerResultSemantics() throws Exception {
        // unknown status → 400
        mockMvc.perform(post("/internal/payouts/{id}/provider-result",
                        "pot_0000000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        // blank status → bean validation 400
        mockMvc.perform(post("/internal/payouts/{id}/provider-result", "pot_x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\" \"}"))
                .andExpect(status().isBadRequest());
        // unknown payout → 404
        mockMvc.perform(post("/internal/payouts/{id}/provider-result",
                        "pot_0000000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        // a negative compensation → 422 return_compensation_impossible with details
        String id = processing();
        mockMvc.perform(post("/internal/payouts/{id}/provider-result", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}")) // → SENT (returnable)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SENT"));
        mockMvc.perform(post("/internal/payouts/{id}/provider-result", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RETURNED\",\"returned_amount_minor\":100}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("return_compensation_impossible"))
                .andExpect(jsonPath("$.error.details.payout_id").value(id))
                .andExpect(jsonPath("$.error.details.reason").value("negative_compensation"));
        // status parsing is case-insensitive and trimmed
        String fresh = processing();
        mockMvc.perform(post("/internal/payouts/{id}/provider-result", fresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\" succeeded \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));
    }

    @Test
    void riskDecisionAppliesDenyAndAllow() throws Exception {
        var payout = env.createDefaultPayout();
        mockMvc.perform(post("/internal/payouts/{id}/risk-decision", payout.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DENY\",\"reason\":\"velocity\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BLOCKED"));
        org.assertj.core.api.Assertions.assertThat(
                env.ledger.effectCount("payouts:" + payout.id() + ":release")).isEqualTo(1);

        var other = env.createPayout("k2");
        mockMvc.perform(post("/internal/payouts/{id}/risk-decision", other.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ALLOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING_RISK"));

        // invalid verdict → 400; unknown payout → 404; late deny → 409
        mockMvc.perform(post("/internal/payouts/{id}/risk-decision", other.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        mockMvc.perform(post("/internal/payouts/{id}/risk-decision",
                        "pot_0000000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ALLOW\"}"))
                .andExpect(status().isNotFound());
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue(); // other → PROCESSING
        mockMvc.perform(post("/internal/payouts/{id}/risk-decision", other.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DENY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"));
    }

    @Test
    void theSchedulerTickReportsTheThreeBatches() throws Exception {
        var payout = env.createDefaultPayout();
        mockMvc.perform(post("/internal/payouts/scheduler/tick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.release_considered").value(1))
                .andExpect(jsonPath("$.release_submitted").value(1))
                .andExpect(jsonPath("$.release_retried").value(0))
                .andExpect(jsonPath("$.release_failed").value(0))
                .andExpect(jsonPath("$.expired_cancelled").value(0))
                .andExpect(jsonPath("$.polls_evaluated").value(1))
                .andExpect(jsonPath("$.id").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(
                env.payouts.findById(payout.id()).orElseThrow().state().wireName())
                .isEqualTo("SENT"); // released then polled to SENT
    }

    @Test
    void theSchedulerTickReportsZerosOnAnEmptyQueue() throws Exception {
        mockMvc.perform(post("/internal/payouts/scheduler/tick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.release_considered").value(0))
                .andExpect(jsonPath("$.release_submitted").value(0))
                .andExpect(jsonPath("$.expired_cancelled").value(0))
                .andExpect(jsonPath("$.polls_evaluated").value(0));
    }
}
