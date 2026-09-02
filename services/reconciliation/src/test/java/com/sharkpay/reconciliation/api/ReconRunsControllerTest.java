package com.sharkpay.reconciliation.api;

import com.sharkpay.reconciliation.api.dto.ReconRunJson;
import com.sharkpay.reconciliation.domain.ReconRunState;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;

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
 * Internal recon-run surface (standalone MockMvc, Jackson 3 NON_NULL, no
 * Spring context): trigger + idempotency semantics (X-Idempotent-Replay,
 * 409 conflict, missing key 400), the auditable FAILED-run shape, list /
 * get / settlement-report reads.
 */
class ReconRunsControllerTest {

    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-02T00:00:00Z";

    private ReconTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new ReconTestEnv();
        mockMvc = env.mockMvc();
        env.seedMatch("hc_clean", 150_000, "KES", 500);
    }

    @Test
    void triggerCreatesTheRunWithCountsBreaksAndReportLinks() throws Exception {
        mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"honeycoin\",\"from\":\"" + FROM
                                + "\",\"to\":\"" + TO + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern("^run_[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.provider").value("honeycoin"))
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.window_from").value(FROM))
                .andExpect(jsonPath("$.window_to").value(TO))
                .andExpect(jsonPath("$.started_at", startsWith("2026-09-01T10:00:00")))
                .andExpect(jsonPath("$.completed_at", startsWith("2026-09-01T10:00:00")))
                .andExpect(jsonPath("$.failure_reason").doesNotExist())
                .andExpect(jsonPath("$.provider_lines").value(1))
                .andExpect(jsonPath("$.internal_lines").value(1))
                .andExpect(jsonPath("$.matched_lines").value(1))
                .andExpect(jsonPath("$.break_count").value(0))
                .andExpect(jsonPath("$.breaks").isEmpty());

        assertThat(env.runs.count()).isEqualTo(1);
        assertThat(env.events.events()).hasSize(1); // run.completed only
    }

    @Test
    void aRunWithBreaksReturnsThemWithTheirFacts() throws Exception {
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);

        mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.break_count").value(1))
                .andExpect(jsonPath("$.breaks[0].id").value(matchesPattern("^brk_[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.breaks[0].run_id").isNotEmpty())
                .andExpect(jsonPath("$.breaks[0].break_type").value("missing_internal"))
                .andExpect(jsonPath("$.breaks[0].provider_ref").value("hc_ghost"))
                .andExpect(jsonPath("$.breaks[0].internal_ref").doesNotExist())
                .andExpect(jsonPath("$.breaks[0].provider_amount.amount_minor").value(2000))
                .andExpect(jsonPath("$.breaks[0].provider_amount.currency").value("KES"))
                .andExpect(jsonPath("$.breaks[0].provider_amount.exponent").value(2))
                .andExpect(jsonPath("$.breaks[0].internal_amount").doesNotExist())
                .andExpect(jsonPath("$.breaks[0].provider_status").value("CONFIRMED"))
                .andExpect(jsonPath("$.breaks[0].state").value("open"))
                .andExpect(jsonPath("$.breaks[0].bucket").value("fresh"))
                .andExpect(jsonPath("$.breaks[0].age_hours").value(0))
                .andExpect(jsonPath("$.breaks[0].detected_at", startsWith("2026-09-01T10:00:00")));
    }

    @Test
    void theSameKeyReplaysTheSameResponseWithNoSecondEffect() throws Exception {
        MvcResult first = trigger("key-1");
        int eventsBefore = env.events.count();

        MvcResult replay = mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(env.runs.count()).isEqualTo(1);
        assertThat(env.events.count()).isEqualTo(eventsBefore);
    }

    @Test
    void theFirstTriggerHasNoReplayHeader() throws Exception {
        mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("X-Idempotent-Replay"));
    }

    @Test
    void theSameKeyWithADifferentPayloadIsA409Conflict() throws Exception {
        trigger("key-1");

        mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, "2026-09-03T00:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"))
                .andExpect(jsonPath("$.error.message").value(containsString("key-1")))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    void aMissingIdempotencyKeyIsA400() throws Exception {
        mockMvc.perform(post("/internal/recon/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.message").value(containsString("Idempotency-Key")));

        assertThat(env.runs.count()).isZero();
    }

    @Test
    void aMalformedBodyOrWindowIsA400NotA500() throws Exception {
        // from >= to is a malformed window (documented 400 on
        // InvalidWindowException — previously fell through to 500)
        mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(TO, FROM)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.message").value(containsString("must be strictly before")));

        // missing fields are bean-validation 400s
        mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));

        // unparseable JSON is a 400
        mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"honeycoin\",\"from\":\"not-a-date\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));

        assertThat(env.runs.count()).isZero();
    }

    @Test
    void anUnavailableStatementSideReturnsTheFailedRunNotAnError() throws Exception {
        env.providers.failNextFetch();

        mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("failed"))
                .andExpect(jsonPath("$.failure_reason").value(containsString(
                        "provider statement unavailable")))
                .andExpect(jsonPath("$.completed_at").isNotEmpty())
                .andExpect(jsonPath("$.breaks").isEmpty())
                .andExpect(jsonPath("$.break_count").value(0));

        // the failed run is idempotent on the same key (deterministic replay)
        mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.state").value("failed"));
    }

    @Test
    void runsAreListedPerProviderNewestFirstWithoutBreakBodies() throws Exception {
        trigger("key-1");
        env.clock.advance(Duration.ofMinutes(30));
        env.providers.seed("hc_late", "CONFIRMED", 1_000, "KES", 0,
                Instant.parse("2026-09-01T12:00:00Z"));
        trigger("key-2");

        mockMvc.perform(get("/internal/recon/runs").param("provider", "honeycoin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(matchesPattern("^run_[0-9a-f]{32}$")))
                .andExpect(jsonPath("$[0].state").value("completed"))
                .andExpect(jsonPath("$[0].break_count").value(1))
                .andExpect(jsonPath("$[0].breaks").doesNotExist());   // summary shape
    }

    @Test
    void theProviderParameterIsMandatoryOnList() throws Exception {
        mockMvc.perform(get("/internal/recon/runs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.message").value(containsString("provider")));
    }

    @Test
    void theRunDetailCarriesItsBreaksWithLiveAging() throws Exception {
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);
        String runId = env.triggerDefault("key-1").run().id();
        env.clock.advance(Duration.ofHours(25));

        mockMvc.perform(get("/internal/recon/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.breaks[0].bucket").value("aging"))
                .andExpect(jsonPath("$.breaks[0].age_hours").value(25));

        mockMvc.perform(get("/internal/recon/runs/{id}", "run_unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void theSettlementReportIsServedPerRunAndPerExactWindow() throws Exception {
        trigger("key-1");

        String runId = env.runs.listByProvider("honeycoin").get(0).id();
        mockMvc.perform(get("/internal/recon/runs/{id}/settlement-report", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").value(runId))
                .andExpect(jsonPath("$.provider").value("honeycoin"))
                .andExpect(jsonPath("$.window_from").value(FROM))
                .andExpect(jsonPath("$.window_to").value(TO))
                .andExpect(jsonPath("$.currencies[0].currency").value("KES"))
                .andExpect(jsonPath("$.currencies[0].provider_lines").value(1))
                .andExpect(jsonPath("$.currencies[0].provider_volume_minor").value(150000))
                .andExpect(jsonPath("$.currencies[0].provider_fees_minor").value(500))
                .andExpect(jsonPath("$.currencies[0].internal_lines").value(1))
                .andExpect(jsonPath("$.currencies[0].internal_volume_minor").value(150000))
                .andExpect(jsonPath("$.currencies[0].matched_lines").value(1))
                .andExpect(jsonPath("$.breaks.total").value(0));

        // a failed run has no report → 404
        ReconTestEnv failedEnv = new ReconTestEnv();
        failedEnv.providers.failNextFetch();
        String failedRunId = failedEnv.triggerDefault("key-f").run().id();
        failedEnv.mockMvc()
                .perform(get("/internal/recon/runs/{id}/settlement-report", failedRunId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        // by exact window
        mockMvc.perform(get("/internal/recon/settlement-report")
                        .param("provider", "honeycoin")
                        .param("from", FROM)
                        .param("to", TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").value(runId));

        mockMvc.perform(get("/internal/recon/settlement-report")
                        .param("provider", "honeycoin")
                        .param("from", "2026-09-05T00:00:00Z")
                        .param("to", "2026-09-06T00:00:00Z"))
                .andExpect(status().isNotFound());

        // provider reports list
        mockMvc.perform(get("/internal/recon/settlement-reports").param("provider", "honeycoin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(1))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(get("/internal/recon/settlement-reports").param("provider", "other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void onlyCompletedRunsCarryCountsOnTheWire() throws Exception {
        assertThat(ReconRunJson.stateCarriesCounts(ReconRunState.COMPLETED)).isTrue();
        assertThat(ReconRunJson.stateCarriesCounts(ReconRunState.RUNNING)).isFalse();
        assertThat(ReconRunJson.stateCarriesCounts(ReconRunState.FAILED)).isFalse();
    }

    private MvcResult trigger(String key) throws Exception {
        return mockMvc.perform(post("/internal/recon/runs")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private static String body(String from, String to) {
        return "{\"provider\":\"honeycoin\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }
}
