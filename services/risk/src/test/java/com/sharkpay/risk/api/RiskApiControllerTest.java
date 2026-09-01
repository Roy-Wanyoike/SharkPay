package com.sharkpay.risk.api;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.domain.CaseStatus;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.events.RiskEventTypes;
import com.sharkpay.risk.fakes.RiskHarness;
import com.sharkpay.risk.fakes.StubRule;
import com.sharkpay.risk.service.AutoCasePolicy;
import com.sharkpay.risk.service.RulesEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskApiControllerTest {

    private RiskHarness harness;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        harness = new RiskHarness();
        mockMvc = mockMvcFor(harness);
    }

    private static MockMvc mockMvcFor(RiskHarness harness) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new RiskApiController(
                        harness.evaluateTransaction, harness.getEvaluation, harness.openCase,
                        harness.getCase, harness.transitionCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private static String evaluationBody(String evaluationId, String kycTier, long amountMinor) {
        return """
                {"evaluation_id":"%s","subject_principal_id":"subject-1",
                 "principal_type":"individual","kyc_tier":"%s",
                 "amount":{"amount_minor":%d,"currency":"KES","exponent":2},
                 "channel":"payment"}
                """.formatted(evaluationId, kycTier, amountMinor).replace("\n", "");
    }

    @Nested
    class Evaluate {

        private static final String EVALUATION_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

        @Test
        void allowsAnInLimitTransactionWith200AndOrderedReasons() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evaluation_id").value(EVALUATION_ID))
                    .andExpect(jsonPath("$.decision").value("allow"))
                    .andExpect(jsonPath("$.reasons[0].rule_id").value("velocity_window"))
                    .andExpect(jsonPath("$.reasons[1].rule_id").value("tier_limit"))
                    .andExpect(jsonPath("$.reasons[2].rule_id").value("geo_denylist"))
                    .andExpect(jsonPath("$.reasons[3].rule_id").value("counterparty_denylist"))
                    .andExpect(jsonPath("$.reasons[0].outcome").value("pass"))
                    .andExpect(jsonPath("$.reasons[0].reason").isNotEmpty());

            assertThat(harness.events.ofType(RiskEventTypes.DECISION_V1)).hasSize(1);
            assertThat(harness.counters.entries()).hasSize(1);
        }

        @Test
        void aDenyIsASuccessfulEvaluationNever422() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody("0d5c9a1e-7b3f-42a1-9c8d-1a2b3c4d5e6f",
                                    "unverified", 1_000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decision").value("deny"))
                    .andExpect(jsonPath("$.reasons[1].rule_id").value("tier_limit"))
                    .andExpect(jsonPath("$.reasons[1].outcome").value("deny"));

            // deny auto-opens a compliance case (default policy) without counting
            assertThat(harness.cases.size()).isEqualTo(1);
            assertThat(harness.counters.entries()).isEmpty();
            assertThat(harness.events.ofType(RiskEventTypes.CASE_OPENED_V1)).hasSize(1);
        }

        @Test
        void aReviewDecisionIsReportedAs200() throws Exception {
            RiskHarness reviewHarness = new RiskHarness(
                    new RulesEngine(StubRule.review("stub_rule")), AutoCasePolicy.DEFAULT);
            MockMvc reviewMockMvc = mockMvcFor(reviewHarness);

            reviewMockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decision").value("review"))
                    .andExpect(jsonPath("$.reasons[0].rule_id").value("stub_rule"))
                    .andExpect(jsonPath("$.reasons[0].outcome").value("review"));

            assertThat(reviewHarness.cases.size()).isEqualTo(1);
        }

        @Test
        void optionalFieldsDefaultAndLenientEnumsAreAccepted() throws Exception {
            String body = """
                    {"evaluation_id":"%s","subject_principal_id":"subject-1",
                     "principal_type":"INDIVIDUAL","kyc_tier":"Limited",
                     "amount":{"amount_minor":1000,"currency":"kes"},
                     "channel":"WALLET","geo_country":"ke","phase":"POST","transaction_type":"payout"}
                    """.formatted(EVALUATION_ID).replace("\n", "");

            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.decision").value("allow"));

            EvaluationRequest stored = harness.evaluations
                    .findById(EVALUATION_ID).orElseThrow().request();
            assertThat(stored.phase().wire()).isEqualTo("post");
            assertThat(stored.transactionType().wire()).isEqualTo("payout");
            assertThat(stored.geoCountry()).isEqualTo("KE");

            com.sharkpay.risk.events.CloudEvent decision = harness.events
                    .ofType(RiskEventTypes.DECISION_V1).get(0);
            assertThat(decision.data())
                    .containsEntry("phase", "post")
                    .containsEntry("transaction_type", "payout");
        }

        @Test
        void idempotentReplayReturnsTheIdenticalResponseWithNoSideEffects() throws Exception {
            MvcResult first = mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)))
                    .andExpect(status().isOk())
                    .andReturn();

            harness.events.reset();
            MvcResult replay = mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(replay.getResponse().getContentAsString())
                    .isEqualTo(first.getResponse().getContentAsString());
            assertThat(harness.events.events()).isEmpty();
            assertThat(harness.counters.entries()).hasSize(1);
            assertThat(harness.evaluations.size()).isEqualTo(1);
        }

        @Test
        void sameEvaluationIdWithADifferentBodyIs409() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 20_000)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("idempotency_conflict"))
                    .andExpect(jsonPath("$.message", containsString(EVALUATION_ID)));

            assertThat(harness.evaluations.size()).isEqualTo(1);
        }

        @Test
        void missingRequiredFieldsAre400() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"principal_type\":\"individual\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"))
                    .andExpect(jsonPath("$.message", containsString("evaluationId")))
                    .andExpect(jsonPath("$.message", containsString("subjectPrincipalId")))
                    .andExpect(jsonPath("$.message", containsString("kycTier")))
                    .andExpect(jsonPath("$.message", containsString("channel")));

            assertThat(harness.evaluations.size()).isZero();
        }

        @Test
        void nullAmountIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)
                                    .replace("\"amount\":{\"amount_minor\":10000,\"currency\":\"KES\",\"exponent\":2}",
                                            "\"amount\":null")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"))
                    .andExpect(jsonPath("$.message", containsString("amount")));
        }

        @Test
        void invalidEnumWireValuesAre400() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)
                                    .replace("\"individual\"", "\"alien\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"))
                    .andExpect(jsonPath("$.message", containsString("principal_type")));

            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "platinum", 10_000)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("kyc_tier")));
        }

        @Test
        void invalidMoneyShapesAre400() throws Exception {
            // non-positive amount
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 0)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"));
            // unsupported currency
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)
                                    .replace("\"KES\"", "\"XYZ\"")))
                    .andExpect(status().isBadRequest());
            // wrong exponent
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)
                                    .replace("\"exponent\":2}", "\"exponent\":3}")))
                    .andExpect(status().isBadRequest());
            // blank currency
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)
                                    .replace("\"KES\"", "\"\"")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void invalidEvaluationIdAndGeoAre400() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody("not-a-uuid", "limited", 10_000)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("evaluation_id")));

            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(EVALUATION_ID, "limited", 10_000)
                                    .replace("\"channel\":\"payment\"", "\"channel\":\"payment\",\"geo_country\":\"KEN\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("geo_country")));
        }

        @Test
        void malformedJsonIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"evaluation_id\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"));
        }
    }

    @Nested
    class GetEvaluationEndpoint {

        @Test
        void fetchesAPersistedEvaluationIncludingUppercaseIds() throws Exception {
            String id = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
            mockMvc.perform(post("/internal/v1/risk/evaluations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evaluationBody(id, "limited", 10_000)))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/internal/v1/risk/evaluations/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evaluation_id").value(id))
                    .andExpect(jsonPath("$.decision").value("allow"))
                    .andExpect(jsonPath("$.reasons").isArray());

            // controller normalizes casing/whitespace before lookup
            mockMvc.perform(get("/internal/v1/risk/evaluations/{id}", id.toUpperCase()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evaluation_id").value(id));
        }

        @Test
        void unknownEvaluationIs404() throws Exception {
            mockMvc.perform(get("/internal/v1/risk/evaluations/{id}",
                            "0d5c9a1e-7b3f-42a1-9c8d-1a2b3c4d5e6f"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("not_found"))
                    .andExpect(jsonPath("$.message", containsString("0d5c9a1e")));
        }
    }

    @Nested
    class Cases {

        @Test
        void opensACaseWith201AndContractShapedBody() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/cases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"subject_principal_id\":\"subject-7\",\"reason\":\"manual review\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.case_id", matchesPattern("case_[0-9a-f]{32}")))
                    .andExpect(jsonPath("$.subject_principal_id").value("subject-7"))
                    .andExpect(jsonPath("$.reason").value("manual review"))
                    .andExpect(jsonPath("$.status").value("open"))
                    .andExpect(jsonPath("$.assigned_to").isEmpty())
                    .andExpect(jsonPath("$.created_at", startsWith("2026-09-01T10:00:00")))
                    .andExpect(jsonPath("$.updated_at", startsWith("2026-09-01T10:00:00")))
                    .andExpect(jsonPath("$.transitions").isArray())
                    .andExpect(jsonPath("$.transitions").isEmpty());

            assertThat(harness.cases.size()).isEqualTo(1);
            assertThat(harness.events.ofType(RiskEventTypes.CASE_OPENED_V1)).hasSize(1);
        }

        @Test
        void blankCaseFieldsAre400() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/cases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"subject_principal_id\":\"\",\"reason\":\"r\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"))
                    .andExpect(jsonPath("$.message", containsString("subjectPrincipalId")));

            mockMvc.perform(post("/internal/v1/risk/cases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"subject_principal_id\":\"s\",\"reason\":\" \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("reason")));
        }

        @Test
        void fetchesACaseByPublicIdOrBareUuid() throws Exception {
            Case opened = harness.openCase.open("subject-1", "reason");

            mockMvc.perform(get("/internal/v1/risk/cases/{id}", opened.publicId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.case_id").value(opened.publicId()))
                    .andExpect(jsonPath("$.status").value("open"));

            mockMvc.perform(get("/internal/v1/risk/cases/{id}", opened.id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.case_id").value(opened.publicId()));
        }

        @Test
        void invalidCaseIdIs400AndUnknownCaseIs404() throws Exception {
            mockMvc.perform(get("/internal/v1/risk/cases/{id}", "case_zzz"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"));

            mockMvc.perform(get("/internal/v1/risk/cases/{id}", "case_00000000000000000000000000000000"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("not_found"));
        }
    }

    @Nested
    class Transitions {

        private Case caseUnderTest() {
            return harness.openCase.open("subject-1", "velocity spike");
        }

        @Test
        void legalChainTransitionsAndReportsTheLog() throws Exception {
            Case c = caseUnderTest();

            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", c.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"under_review\",\"actor\":\"op-1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("under_review"))
                    .andExpect(jsonPath("$.transitions[0].from").value("open"))
                    .andExpect(jsonPath("$.transitions[0].to").value("under_review"))
                    .andExpect(jsonPath("$.transitions[0].actor").value("op-1"))
                    .andExpect(jsonPath("$.transitions[0].resolution").isEmpty())
                    .andExpect(jsonPath("$.transitions[0].occurred_at", startsWith("2026-09-01T10:00")));

            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", c.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"escalated\",\"actor\":\"op-2\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("escalated"));

            // de-escalation is a legal edge
            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", c.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"under_review\",\"actor\":\"op-2\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("under_review"));

            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", c.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"closed\",\"actor\":\"op-3\",\"resolution\":\"sar_filed\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("closed"))
                    .andExpect(jsonPath("$.transitions[3].to").value("closed"))
                    .andExpect(jsonPath("$.transitions[3].resolution").value("sar_filed"));

            assertThat(harness.events.ofType(RiskEventTypes.CASE_RESOLVED_V1)).hasSize(1);
            assertThat(harness.events.ofType(RiskEventTypes.CASE_OPENED_V1)).hasSize(1);
        }

        @Test
        void closingWithoutResolutionDefaultsToCleared() throws Exception {
            Case c = caseUnderTest();
            harness.transitionCase.transition(c.publicId(), CaseStatus.UNDER_REVIEW, "op-1", null);

            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", c.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CLOSED\",\"actor\":\"op-9\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("closed"))
                    .andExpect(jsonPath("$.transitions[1].resolution").value("cleared"));

            assertThat(harness.events.ofType(RiskEventTypes.CASE_RESOLVED_V1)).hasSize(1);
            assertThat(harness.events.last().orElseThrow().data())
                    .containsEntry("resolution", "cleared")
                    .containsEntry("resolved_by", "op-9");
        }

        @Test
        void illegalAndTerminalTransitionsAre409() throws Exception {
            Case open = caseUnderTest();
            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", open.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"closed\",\"actor\":\"op-1\",\"resolution\":\"cleared\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("state_conflict"))
                    .andExpect(jsonPath("$.message", containsString("open")));

            Case closed = caseUnderTest();
            harness.transitionCase.transition(closed.publicId(), CaseStatus.UNDER_REVIEW, "op-1", null);
            harness.transitionCase.transition(closed.publicId(), CaseStatus.CLOSED, "op-2",
                    com.sharkpay.risk.domain.CaseResolution.CLEARED);
            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", closed.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"under_review\",\"actor\":\"op-1\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("state_conflict"));
        }

        @Test
        void invalidTransitionRequestsAre400() throws Exception {
            Case c = caseUnderTest();

            // unknown status wire value
            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", c.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"frozen\",\"actor\":\"op-1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"))
                    .andExpect(jsonPath("$.message", containsString("status")));

            // blank actor fails bean validation
            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", c.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"under_review\",\"actor\":\" \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("actor")));

            // resolution is only allowed when closing
            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", c.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"under_review\",\"actor\":\"op-1\",\"resolution\":\"cleared\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("resolution")));

            // unknown resolution wire value
            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", c.publicId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"under_review\",\"actor\":\"op-1\",\"resolution\":\"maybe\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("resolution")));
        }

        @Test
        void unknownCaseIs404AndInvalidIdIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions",
                            "case_00000000000000000000000000000000")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"under_review\",\"actor\":\"op-1\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("not_found"));

            mockMvc.perform(post("/internal/v1/risk/cases/{id}/transitions", "garbage")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"under_review\",\"actor\":\"op-1\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"));
        }
    }
}
