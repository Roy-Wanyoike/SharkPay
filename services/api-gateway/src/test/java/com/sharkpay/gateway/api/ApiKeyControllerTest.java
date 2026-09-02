package com.sharkpay.gateway.api;

import com.sharkpay.gateway.testsupport.GatewayTestEnv;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API key management endpoints (webhooks.yaml-adjacent management surface,
 * scope apikeys:manage): create with the plaintext secret exactly once,
 * idempotent replays (secret redacted), rotation, revocation, listing, and
 * the 400/404/409 error semantics of the common error envelope.
 */
class ApiKeyControllerTest {

    private final GatewayTestEnv env = new GatewayTestEnv();
    private final MockMvc mvc = env.mockMvc();

    private static final String CREATE_BODY = """
            {"scopes": ["payments:read", "payments:write"], "rpm_limit": 120,
             "monthly_limit": 5000}
            """;

    private GatewayTestEnv.SeededKey adminKey() {
        return env.seedFullKey(env.newPrincipal());
    }

    @Test
    void createReturnsThePlaintextSecretExactlyOnce() throws Exception {
        GatewayTestEnv.SeededKey key = adminKey();
        UUID principal = key.key().principalId();

        String body = mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-create-1")
                        .contentType("application/json")
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.principal_id").value(principal.toString()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.scopes.length()").value(2))
                .andExpect(jsonPath("$.grace_expires_at").doesNotExist())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.rpm_limit").value(120))
                .andExpect(jsonPath("$.monthly_limit").value(5000))
                .andExpect(jsonPath("$.created_at").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // the store only ever holds the hash (hash-never-plaintext)
        String secret = readSecret(body);
        assertTrue(secret.startsWith("sp_live_"));
        for (com.sharkpay.gateway.domain.ApiKey stored : env.keys.all().values()) {
            assertFalse(stored.secretHash().startsWith("sp_live_"));
            assertFalse(env.keys.all().toString().contains(secret));
        }
    }

    @Test
    void idempotentReplayReturnsTheStoredKeyRedacted() throws Exception {
        GatewayTestEnv.SeededKey key = adminKey();
        String first = mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-create-2")
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String plaintextOnce = readSecret(first);

        String replay = mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-create-2")
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertFalse(replay.contains(plaintextOnce),
                "a replay must never repeat the plaintext secret");
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-create-2")
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"));
    }

    @Test
    void theSameIdempotencyKeyWithADifferentPayloadIs409() throws Exception {
        GatewayTestEnv.SeededKey key = adminKey();
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-create-3")
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated());

        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-create-3")
                        .contentType("application/json")
                        .content("{\"scopes\": [\"fx:read\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
    }

    @Test
    void missingIdempotencyKeyUnknownScopesAndBodiesAre400() throws Exception {
        GatewayTestEnv.SeededKey key = adminKey();
        // missing header
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.details.header").value("Idempotency-Key"));
        // unknown scope: fail-closed catalog
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-4")
                        .contentType("application/json")
                        .content("{\"scopes\": [\"payments:write\", \"typo:scope\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        // empty scopes
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-5")
                        .contentType("application/json").content("{\"scopes\": []}"))
                .andExpect(status().isBadRequest());
        // malformed JSON
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-6")
                        .contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rotateReturnsTheNewSecretAndGraceMetadata() throws Exception {
        UUID principal = env.newPrincipal();
        GatewayTestEnv.SeededKey key = env.seedKey(principal,
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.API_KEYS_MANAGE), 300,
                2_000_000L);
        env.seedKey(principal, java.util.Set.of(com.sharkpay.gateway.domain.Scope.PAYMENTS_READ),
                33, 44L); // a second key of the same principal

        mvc.perform(post("/v1/api-keys/{id}/rotate", key.key().id())
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-rotate-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.grace_expires_at").doesNotExist());

        // the listing shows the demoted (ROTATING) key with its grace window
        String listing = mvc.perform(get("/v1/api-keys")
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertTrue(listing.contains("\"status\":\"rotating\""), listing);
        assertTrue(listing.contains("grace_expires_at"), listing);
    }

    @Test
    void revokeIs204AndIdempotentThen404() throws Exception {
        UUID principal = env.newPrincipal();
        GatewayTestEnv.SeededKey manager = env.seedKey(principal,
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.API_KEYS_MANAGE), 300,
                2_000_000L);
        GatewayTestEnv.SeededKey victim = env.seedKey(principal,
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.PAYMENTS_READ), 300,
                2_000_000L);

        mvc.perform(post("/v1/api-keys/{id}/revoke", victim.key().id())
                        .header("Authorization", manager.authorization()))
                .andExpect(status().isNoContent());
        // the revoked secret no longer authenticates
        mvc.perform(get("/v1/payments").header("Authorization", victim.authorization()))
                .andExpect(status().isUnauthorized());
        // revoking again is still 204 (idempotent)
        mvc.perform(post("/v1/api-keys/{id}/revoke", victim.key().id())
                        .header("Authorization", manager.authorization()))
                .andExpect(status().isNoContent());
        // foreign keys are indistinguishable from missing ones
        mvc.perform(post("/v1/api-keys/{id}/revoke", "key_doesnotexist00000000000")
                        .header("Authorization", manager.authorization()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listIsScopedToTheCallerAndNeverCarriesSecrets() throws Exception {
        GatewayTestEnv.SeededKey mine = adminKey();
        adminKey(); // another principal's key — must not leak into my listing
        String body = mvc.perform(get("/v1/api-keys")
                        .header("Authorization", mine.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("sp_live_fake"), body);
        assertFalse(body.contains("\"secret\""), body);
    }

    @Test
    void listValidatesTheLimitParameter() throws Exception {
        GatewayTestEnv.SeededKey key = adminKey();
        mvc.perform(get("/v1/api-keys?limit=0").header("Authorization", key.authorization()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/api-keys?limit=101").header("Authorization", key.authorization()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/api-keys?limit=1").header("Authorization", key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void rotatingARotatingKeyIsA409StateConflict() throws Exception {
        UUID principal = env.newPrincipal();
        GatewayTestEnv.SeededKey key = env.seedKey(principal,
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.API_KEYS_MANAGE), 300,
                2_000_000L);
        mvc.perform(post("/v1/api-keys/{id}/rotate", key.key().id())
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "rot-1"))
                .andExpect(status().isCreated());
        // the demoted (ROTATING) key cannot rotate again
        mvc.perform(post("/v1/api-keys/{id}/rotate", key.key().id())
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "rot-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"));
    }

    @Test
    void rotateReplayIsRedactedWithTheHeader() throws Exception {
        UUID principal = env.newPrincipal();
        GatewayTestEnv.SeededKey key = env.seedKey(principal,
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.API_KEYS_MANAGE), 300,
                2_000_000L);
        mvc.perform(post("/v1/api-keys/{id}/rotate", key.key().id())
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-rotate-replay"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").isNotEmpty());

        // same idempotency key + same rotation target: redacted replay
        String replay = mvc.perform(post("/v1/api-keys/{id}/rotate", key.key().id())
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-rotate-replay"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(replay.contains("sp_live_fake"), replay);

        // the same key rotating a DIFFERENT key id is a conflict
        mvc.perform(post("/v1/api-keys/{id}/rotate", "key_other000000000000000000")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-rotate-replay"))
                .andExpect(status().isConflict());
    }

    @Test
    void aReplayWhoseStoredEntityVanishedIs404() throws Exception {
        GatewayTestEnv.SeededKey key = adminKey();
        String body = mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-vanishing")
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String createdId = readId(body);

        // the idempotency cache references an entity the store no longer has
        env.keys.delete(createdId);
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-vanishing")
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    /** Extracts the id field of a response body (test-only helper). */
    private static String readId(String body) {
        int index = body.indexOf("\"id\":\"");
        assertTrue(index >= 0, body);
        int start = index + "\"id\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    /** Extracts the secret field of a response body (test-only helper). */
    private static String readSecret(String body) {
        int index = body.indexOf("\"secret\":\"");
        assertTrue(index >= 0, body);
        int start = index + "\"secret\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
