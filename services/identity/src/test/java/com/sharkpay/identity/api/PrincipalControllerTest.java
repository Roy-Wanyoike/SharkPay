package com.sharkpay.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sharkpay.identity.api.dto.EnumParser;
import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.exception.ValidationException;
import com.sharkpay.identity.fakes.IdentityHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class PrincipalControllerTest {

    private IdentityHarness harness;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        harness = new IdentityHarness();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new PrincipalController(
                        harness.createPrincipal, harness.createAgent, harness.getPrincipal,
                        harness.changeStatus, harness.verifyKyc))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Nested
    class CreatePrincipal {

        @Test
        void createsAnIndividualPrincipalWith201() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"INDIVIDUAL\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.shark_id").value(
                            org.hamcrest.Matchers.matchesPattern("SP-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}")))
                    .andExpect(jsonPath("$.type").value("INDIVIDUAL"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.kyc_tier").value("UNVERIFIED"))
                    .andExpect(jsonPath("$.owner_principal_id").isEmpty())
                    .andExpect(jsonPath("$.created_at", startsWith("2026-09-01T10:00:30")));

            assertThat(harness.events.byType("identity.principal.created.v1")).hasSize(1);
        }

        @Test
        void createsAnAgentPrincipalWithAnOwner() throws Exception {
            Principal owner = harness.individual();

            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGENT\",\"owner_shark_id\":\"" + owner.sharkId().value() + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("AGENT"))
                    .andExpect(jsonPath("$.owner_principal_id").value(owner.id().toString()));

            assertThat(harness.events.last().data()).containsEntry("owner_principal_id", owner.id().toString());
        }

        @Test
        void caseInsensitiveEnumsAreAccepted() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"business\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("BUSINESS"));
        }

        @Test
        void idempotentReplayReturnsTheOriginalPrincipalWith200() throws Exception {
            MvcResult first = mockMvc.perform(post("/internal/v1/principals")
                            .header("Idempotency-Key", "key-42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"INDIVIDUAL\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            harness.events.reset();
            MvcResult replay = mockMvc.perform(post("/internal/v1/principals")
                            .header("Idempotency-Key", "key-42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"INDIVIDUAL\"}"))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(replay.getResponse().getContentAsString())
                    .isEqualTo(first.getResponse().getContentAsString());
            assertThat(harness.events.events()).isEmpty();
            assertThat(harness.principals.count()).isEqualTo(1);
        }

        @Test
        void idempotencyKeyWithADifferentBodyReturns409() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .header("Idempotency-Key", "key-42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"INDIVIDUAL\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/internal/v1/principals")
                            .header("Idempotency-Key", "key-42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"BUSINESS\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                    .andExpect(jsonPath("$.message", containsString("key-42")));

            assertThat(harness.principals.count()).isEqualTo(1);
        }

        @Test
        void oversizedIdempotencyKeyIsRejected() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .header("Idempotency-Key", "k".repeat(200))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"INDIVIDUAL\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        }

        @Test
        void invalidTypeIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"WHATEVER\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PRINCIPAL_TYPE"))
                    .andExpect(jsonPath("$.message", containsString("WHATEVER")));
        }

        @Test
        void missingTypeIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void agentWithoutOwnerIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGENT\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OWNER_REQUIRED"));
        }

        @Test
        void individualWithOwnerIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"INDIVIDUAL\",\"owner_shark_id\":\"SP-0000-0001\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OWNER_NOT_ALLOWED"));
        }

        @Test
        void unknownOwnerIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGENT\",\"owner_shark_id\":\"SP-0000-0001\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OWNER_NOT_FOUND"));
        }

        @Test
        void invalidOwnerSharkIdFormatIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"AGENT\",\"owner_shark_id\":\"not-a-shark-id\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SHARK_ID"));
        }

        @Test
        void malformedBodyIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/principals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_BODY"));
        }
    }

    @Nested
    class GetPrincipal {

        @Test
        void getByPrincipalId() throws Exception {
            Principal principal = harness.individual();

            mockMvc.perform(get("/internal/v1/principals/{id}", principal.id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(principal.id().toString()))
                    .andExpect(jsonPath("$.shark_id").value(principal.sharkId().value()))
                    .andExpect(jsonPath("$.type").value("INDIVIDUAL"));
        }

        @Test
        void unknownPrincipalIdIs404() throws Exception {
            mockMvc.perform(get("/internal/v1/principals/{id}", java.util.UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRINCIPAL_NOT_FOUND"));
        }

        @Test
        void invalidPrincipalIdIs400() throws Exception {
            mockMvc.perform(get("/internal/v1/principals/{id}", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_UUID"));
        }

        @Test
        void getBySharkId() throws Exception {
            Principal principal = harness.individual();

            mockMvc.perform(get("/internal/v1/sharkids/{sharkId}", principal.sharkId().value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(principal.id().toString()));
        }

        @Test
        void unknownSharkIdIs404() throws Exception {
            String unknown = SharkId.fromData("ZZZZZZ").value();
            mockMvc.perform(get("/internal/v1/sharkids/{sharkId}", unknown))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRINCIPAL_NOT_FOUND"));
        }

        @Test
        void malformedSharkIdIs400() throws Exception {
            mockMvc.perform(get("/internal/v1/sharkids/{sharkId}", "SP-0000-0002"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SHARK_ID"));
        }
    }

    @Nested
    class Kyc {

        @Test
        void approvedDecisionAdvancesTierAndReturnsBothViews() throws Exception {
            Principal principal = harness.individual();

            mockMvc.perform(post("/internal/v1/principals/{id}/kyc", principal.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tier\":\"LIMITED\",\"status\":\"APPROVED\",\"provider_ref\":\"prov-1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.principal.kyc_tier").value("LIMITED"))
                    .andExpect(jsonPath("$.principal.id").value(principal.id().toString()))
                    .andExpect(jsonPath("$.kyc.tier").value("LIMITED"))
                    .andExpect(jsonPath("$.kyc.status").value("APPROVED"))
                    .andExpect(jsonPath("$.kyc.provider_ref").value("prov-1"))
                    .andExpect(jsonPath("$.kyc.decided_at", startsWith("2026-09-01")));

            assertThat(harness.events.byType("identity.kyc.tier.changed.v1")).hasSize(1);
        }

        @Test
        void pendingDecisionRecordsWithoutTierChange() throws Exception {
            Principal principal = harness.individual();

            mockMvc.perform(post("/internal/v1/principals/{id}/kyc", principal.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tier\":\"LIMITED\",\"status\":\"PENDING\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.principal.kyc_tier").value("UNVERIFIED"))
                    .andExpect(jsonPath("$.kyc.status").value("PENDING"))
                    .andExpect(jsonPath("$.kyc.decided_at").isEmpty());

            assertThat(harness.events.events()).isEmpty();
        }

        @Test
        void illegalTierJumpIs409() throws Exception {
            Principal principal = harness.individual();

            mockMvc.perform(post("/internal/v1/principals/{id}/kyc", principal.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tier\":\"FULL\",\"status\":\"APPROVED\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_TIER_TRANSITION"));

            assertThat(harness.kycRecords.all()).isEmpty();
        }

        @Test
        void closedPrincipalIs409() throws Exception {
            Principal closed = harness.closedIndividual();

            mockMvc.perform(post("/internal/v1/principals/{id}/kyc", closed.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tier\":\"LIMITED\",\"status\":\"APPROVED\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRINCIPAL_CLOSED"));
        }

        @Test
        void unknownPrincipalIs404() throws Exception {
            mockMvc.perform(post("/internal/v1/principals/{id}/kyc", java.util.UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tier\":\"LIMITED\",\"status\":\"APPROVED\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void invalidTierOrStatusIs400() throws Exception {
            Principal principal = harness.individual();
            mockMvc.perform(post("/internal/v1/principals/{id}/kyc", principal.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tier\":\"SILVER\",\"status\":\"APPROVED\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_KYC_TIER"));
            mockMvc.perform(post("/internal/v1/principals/{id}/kyc", principal.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tier\":\"LIMITED\",\"status\":\"MAYBE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_KYC_STATUS"));
        }
    }

    @Nested
    class CreateAgent {

        @Test
        void createsAnAgentWith201() throws Exception {
            Principal owner = harness.individual();

            mockMvc.perform(post("/internal/v1/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"owner_shark_id\":\"" + owner.sharkId().value() + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("AGENT"))
                    .andExpect(jsonPath("$.owner_principal_id").value(owner.id().toString()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        void unknownOwnerIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"owner_shark_id\":\"" + SharkId.fromData("ZZZZZZ").value() + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OWNER_NOT_FOUND"));
        }

        @Test
        void blankOwnerIs400() throws Exception {
            mockMvc.perform(post("/internal/v1/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"owner_shark_id\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class ChangeStatus {

        @Test
        void suspendsWith200AndResetEvent() throws Exception {
            Principal principal = harness.individualWithTier(KycTier.LIMITED);

            mockMvc.perform(post("/internal/v1/principals/{id}/status", principal.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"SUSPENDED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUSPENDED"))
                    .andExpect(jsonPath("$.kyc_tier").value("UNVERIFIED"));

            assertThat(harness.events.byType("identity.principal.status.changed.v1")).hasSize(1);
            assertThat(harness.events.byType("identity.kyc.tier.changed.v1")).hasSize(1);
        }

        @Test
        void closedIsTerminal409() throws Exception {
            Principal closed = harness.closedIndividual();

            mockMvc.perform(post("/internal/v1/principals/{id}/status", closed.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACTIVE\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATUS_TRANSITION"));
        }

        @Test
        void invalidStatusValueIs400() throws Exception {
            Principal principal = harness.individual();
            mockMvc.perform(post("/internal/v1/principals/{id}/status", principal.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"FROZEN\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PRINCIPAL_STATUS"));
        }

        @Test
        void unknownPrincipalIs404() throws Exception {
            mockMvc.perform(post("/internal/v1/principals/{id}/status", java.util.UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"SUSPENDED\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class EnumParserContract {

        @Test
        void parsesCaseInsensitivelyAndRejectsUnknownValues() {
            assertThat(EnumParser.parse(PrincipalStatus.class, "active", "INVALID_PRINCIPAL_STATUS"))
                    .isEqualTo(PrincipalStatus.ACTIVE);
            assertThat(EnumParser.parse(PrincipalStatus.class, " closed ", "INVALID_PRINCIPAL_STATUS"))
                    .isEqualTo(PrincipalStatus.CLOSED);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> EnumParser.parse(PrincipalStatus.class, "FROZEN", "INVALID_PRINCIPAL_STATUS"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("FROZEN")
                    .hasMessageContaining("[ACTIVE, SUSPENDED, CLOSED]");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> EnumParser.parse(PrincipalStatus.class, null, "INVALID_PRINCIPAL_STATUS"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("blank");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> EnumParser.parse(PrincipalStatus.class, "  ", "INVALID_PRINCIPAL_STATUS"))
                    .isInstanceOf(ValidationException.class);
        }
    }
}
