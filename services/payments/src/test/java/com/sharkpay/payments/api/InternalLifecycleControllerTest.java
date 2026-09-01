package com.sharkpay.payments.api;

import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal (service-to-service) lifecycle adapter: provider-result intake,
 * reversal and the append-only transition timeline.
 */
class InternalLifecycleControllerTest {

    private PaymentsTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new PaymentsTestEnv();
        mockMvc = env.mockMvc();
    }

    @Test
    void providerResultConfirmsCapturesAndReplays() throws Exception {
        String id = env.createDefault().id();

        // PROCESSING → 200 PROCESSING (no event, no money moved)
        mockMvc.perform(post("/internal/payments/{id}/provider-result", id)
                        .header("Idempotency-Key", "irk-1").contentType("application/json")
                        .content("{\"status\": \"PROCESSING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PROCESSING"));

        // SUCCEEDED → capture → 200 SUCCEEDED
        mockMvc.perform(post("/internal/payments/{id}/provider-result", id)
                        .header("Idempotency-Key", "irk-2").contentType("application/json")
                        .content("{\"status\": \"SUCCEEDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));

        // replay of the same key + status → replay header, no double capture
        mockMvc.perform(post("/internal/payments/{id}/provider-result", id)
                        .header("Idempotency-Key", "irk-2").contentType("application/json")
                        .content("{\"status\": \"SUCCEEDED\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));
        org.assertj.core.api.Assertions.assertThat(env.walletHolds.capturedHolds()).hasSize(1);
    }

    @Test
    void providerResultFailuresCompensateAndValidate() throws Exception {
        String id = env.createDefault().id();

        mockMvc.perform(post("/internal/payments/{id}/provider-result", id)
                        .contentType("application/json").content("{\"status\": \"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.failure_reason").value("provider_failed"));

        // key reuse with a different status → 409 idempotency_conflict
        String other = env.create("k-other").id();
        mockMvc.perform(post("/internal/payments/{id}/provider-result", other)
                        .header("Idempotency-Key", "irk-x").contentType("application/json")
                        .content("{\"status\": \"PENDING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/payments/{id}/provider-result", other)
                        .header("Idempotency-Key", "irk-x").contentType("application/json")
                        .content("{\"status\": \"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));

        // unknown status → 400; blank status → 400; unknown payment → 404
        mockMvc.perform(post("/internal/payments/{id}/provider-result", other)
                        .contentType("application/json").content("{\"status\": \"BOGUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        mockMvc.perform(post("/internal/payments/{id}/provider-result", other)
                        .contentType("application/json").content("{\"status\": \"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/internal/payments/{id}/provider-result",
                        "pay_0123456789abcdef0123456789abcdee")
                        .contentType("application/json").content("{\"status\": \"PENDING\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reverseAppliesTheGuardAndCompensationPair() throws Exception {
        String id = env.createDefault().id();
        env.recordResult.record(null, id, "SUCCEEDED");

        mockMvc.perform(post("/internal/payments/{id}/reverse", id)
                        .header("Idempotency-Key", "rk-1").contentType("application/json")
                        .content("{\"amount_minor\": 60000, \"reason\": \"partial\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVERSED"));

        // above the captured amount → 422 reversal_exceeds_captured
        String other = env.create("k-other").id();
        env.recordResult.record(null, other, "SUCCEEDED");
        mockMvc.perform(post("/internal/payments/{id}/reverse", other)
                        .header("Idempotency-Key", "rk-2").contentType("application/json")
                        .content("{\"amount_minor\": 999999, \"reason\": \"x\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("reversal_exceeds_captured"));

        // non-reversible state → 409 state_conflict; unknown → 404
        String pending = env.create("k-pending").id();
        mockMvc.perform(post("/internal/payments/{id}/reverse", pending)
                        .header("Idempotency-Key", "rk-3").contentType("application/json")
                        .content("{\"reason\": \"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"));
        mockMvc.perform(post("/internal/payments/{id}/reverse",
                        "pay_0123456789abcdef0123456789abcdee")
                        .header("Idempotency-Key", "rk-4").contentType("application/json")
                        .content("{\"reason\": \"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reverseReplayReturnsTheReversalWithTheHeader() throws Exception {
        String id = env.createDefault().id();
        env.recordResult.record(null, id, "SUCCEEDED");

        mockMvc.perform(post("/internal/payments/{id}/reverse", id)
                .header("Idempotency-Key", "rk-1").contentType("application/json")
                .content("{\"reason\": \"ops\"}")).andReturn();
        mockMvc.perform(post("/internal/payments/{id}/reverse", id)
                        .header("Idempotency-Key", "rk-1").contentType("application/json")
                        .content("{\"reason\": \"ops\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.state").value("REVERSED"));
    }

    @Test
    void transitionsExposeTheAppendOnlyTimeline() throws Exception {
        String id = env.createDefault().id();
        env.recordResult.record(null, id, "SUCCEEDED");

        mockMvc.perform(get("/internal/payments/{id}/transitions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].to").value("CREATED"))
                .andExpect(jsonPath("$[1].to").value("PENDING_PROVIDER"))
                .andExpect(jsonPath("$[2].to").value("PROCESSING"))
                .andExpect(jsonPath("$[3].to").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].from").doesNotExist())
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[3].occurredAt", startsWith("2026-09-01")));

        mockMvc.perform(get("/internal/payments/{id}/transitions",
                        "pay_0123456789abcdef0123456789abcdee"))
                .andExpect(status().isNotFound());
    }
}
