package com.sharkpay.gateway.api;

import com.sharkpay.gateway.domain.Scope;
import com.sharkpay.gateway.service.RotateApiKeyUseCase;
import com.sharkpay.gateway.testsupport.GatewayTestEnv;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API key auth filter — the gateway front door: 401 for
 * missing/malformed/unknown/revoked/grace-expired secrets, 403 for unknown
 * route classes and missing scopes (fail-closed), 429 + Retry-After for
 * exhausted quotas, and request attributes on success. Everything runs
 * through standalone MockMvc with the filter attached exactly like
 * production (ADR 003: no Spring context).
 */
class ApiKeyAuthFilterTest {

    private final GatewayTestEnv env = new GatewayTestEnv();
    private final MockMvc mvc = env.mockMvc();

    private static final String VALID_EVENT = """
            {
              "id": "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
              "type": "payments.payment.succeeded.v1",
              "specversion": "1.0",
              "source": "sharkpay/payments",
              "subject": "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
              "occurred_at": "2026-09-01T10:00:05Z",
              "data": {"payment_id": "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "state": "SUCCEEDED"}
            }
            """;

    @Test
    void missingAuthorizationHeaderIs401() throws Exception {
        mvc.perform(get("/v1/payments")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    void nonBearerSchemesAndMalformedSecretsAre401() throws Exception {
        mvc.perform(get("/v1/payments").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
        mvc.perform(get("/v1/payments").header("Authorization", "Token sp_live_something"))
                .andExpect(status().isUnauthorized());
        // not the sk_ format
        mvc.perform(get("/v1/payments").header("Authorization", "Bearer not-a-key"))
                .andExpect(status().isUnauthorized());
        // too short
        mvc.perform(get("/v1/payments").header("Authorization", "Bearer sp_live_short"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownSecretsAre401() throws Exception {
        mvc.perform(get("/v1/payments")
                        .header("Authorization", "Bearer sp_live_" + "x".repeat(43)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void revokedKeysAre401() throws Exception {
        UUID principal = env.newPrincipal();
        GatewayTestEnv.SeededKey key = env.seedFullKey(principal);
        env.keyAdmin.revoke(key.key().id(), principal);

        mvc.perform(get("/v1/payments").header("Authorization", key.authorization()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void graceExpiredRotatingKeysAre401UntilExpiryTheyWork() throws Exception {
        UUID principal = env.newPrincipal();
        GatewayTestEnv.SeededKey old = env.seedFullKey(principal);

        // inside the 24 h grace window the old secret still authenticates
        env.clock.advance(Duration.ofHours(23));
        mvc.perform(get("/v1/payments").header("Authorization", old.authorization()))
                .andExpect(status().isOk());

        // rotate: the old secret is demoted with a 24 h grace window
        RotateApiKeyUseCase.Result rotation = env.rotateKey.rotate(old.key().id(), principal);
        mvc.perform(get("/v1/payments").header("Authorization", old.authorization()))
                .andExpect(status().isOk());

        // after grace expiry the old secret is rejected like an unknown key
        env.clock.set(rotation.demoted().graceExpiresAt().plusSeconds(1));
        mvc.perform(get("/v1/payments").header("Authorization", old.authorization()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRouteClassIs403FailClosed() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedFullKey(env.newPrincipal());
        // a well-formed key on a path no route class is registered for
        mvc.perform(get("/v1/nope").header("Authorization", key.authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"))
                .andExpect(jsonPath("$.error.details.path").value("/v1/nope"));
    }

    @Test
    void missingScopeIs403WithTheRequiredScopeInDetails() throws Exception {
        // read-only key on a mutating payments call
        GatewayTestEnv.SeededKey readKey = env.seedKey(env.newPrincipal(),
                Set.of(Scope.PAYMENTS_READ), 300, 2_000_000L);
        mvc.perform(post("/v1/payments").header("Authorization", readKey.authorization())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"))
                .andExpect(jsonPath("$.error.details.required_scope")
                        .value("payments:write"));

        // wallets has no write scope at all → even a full key cannot POST:
        // fail-closed with the path named (no satisfiable scope exists)
        GatewayTestEnv.SeededKey full = env.seedFullKey(env.newPrincipal());
        mvc.perform(post("/v1/wallets").header("Authorization", full.authorization())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.details.path").value("/v1/wallets"));
    }

    @Test
    void quotaExceededIs429WithRetryAfter() throws Exception {
        // rpm limit 2: two calls pass, the third is 429 within the same minute
        GatewayTestEnv.SeededKey key = env.seedKey(env.newPrincipal(), Set.of(Scope.values()),
                2, 2_000_000L);
        mvc.perform(get("/v1/payments").header("Authorization", key.authorization()))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/payments").header("Authorization", key.authorization()))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/payments").header("Authorization", key.authorization()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("quota_exceeded"))
                .andExpect(jsonPath("$.error.details.window").value("minute"))
                .andExpect(jsonPath("$.error.details.retry_after_seconds").value(60))
                .andExpect(header().string("Retry-After", "60"));

        // the window rolls: one minute later the quota resets
        env.clock.advance(Duration.ofSeconds(61));
        mvc.perform(get("/v1/payments").header("Authorization", key.authorization()))
                .andExpect(status().isOk());
    }

    @Test
    void monthlyQuotaExceededIs429WithTheMonthWindow() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedKey(env.newPrincipal(), Set.of(Scope.values()),
                300, 1L);
        mvc.perform(get("/v1/payments").header("Authorization", key.authorization()))
                .andExpect(status().isOk());
        // monthly limit 1 already consumed: the next call fails on the month
        // window even in a fresh minute
        env.clock.advance(Duration.ofMinutes(2));
        mvc.perform(get("/v1/payments").header("Authorization", key.authorization()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.details.window").value("monthly"));
    }

    @Test
    void nonApiSurfacesBypassTheFilter() throws Exception {
        // /internal/events carries no Authorization header and still reaches
        // the controller (it is a private-surface path in production)
        mvc.perform(post("/internal/events").contentType("application/json")
                        .content(VALID_EVENT))
                .andExpect(status().isAccepted());
    }

    @Test
    void authenticatedRequestResolvesThePrincipalAndFailsLoudlyWithoutIt() {
        UUID principal = env.newPrincipal();
        GatewayTestEnv.SeededKey key = env.seedFullKey(principal);
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.ATTR_KEY_ID, key.key().id());
        request.setAttribute(ApiKeyAuthFilter.ATTR_PRINCIPAL_ID, principal.toString());
        assertEquals(principal, AuthenticatedRequest.principal(request));
        assertEquals(key.key().id(), AuthenticatedRequest.apiKeyId(request));

        org.springframework.mock.web.MockHttpServletRequest bare =
                new org.springframework.mock.web.MockHttpServletRequest();
        assertThrows(IllegalStateException.class, () -> AuthenticatedRequest.principal(bare));
        assertThrows(IllegalStateException.class, () -> AuthenticatedRequest.apiKeyId(bare));
    }

    @Test
    void theErrorBodyMatchesTheCommonErrorEnvelopeShape() throws Exception {
        String body = mvc.perform(get("/v1/nope")
                        .header("Authorization",
                                env.seedFullKey(env.newPrincipal()).authorization()))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        // the hand-written envelope carries code/message/request_id/details
        // and never any secret material
        assertTrue(body.contains("\"code\":\"forbidden\""), body);
        assertTrue(body.contains("request_id"), body);
        assertTrue(body.contains("\"path\":\"/v1/nope\""), body);
    }
}
