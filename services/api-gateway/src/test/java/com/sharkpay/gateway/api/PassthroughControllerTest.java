package com.sharkpay.gateway.api;

import com.sharkpay.gateway.ports.UpstreamPort.UpstreamResponse;
import com.sharkpay.gateway.testsupport.GatewayTestEnv;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /v1 passthrough skeleton over the fake upstream: principal (never the
 * API key) propagates upstream, idempotent response caching replays with
 * X-Idempotent-Replay, and the upstream status/body relay verbatim.
 */
class PassthroughControllerTest {

    private final GatewayTestEnv env = new GatewayTestEnv();
    private final MockMvc mvc = env.mockMvc();

    @Test
    void forwardsWithPrincipalPropagationAndRelaysVerbatim() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedFullKey(env.newPrincipal());
        env.upstream.respondWith(request -> new UpstreamResponse(201,
                "{\"payment_id\":\"pay_1\",\"state\":\"CREATED\"}"));

        mvc.perform(post("/v1/payments")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-passthrough-1")
                        .contentType("application/json")
                        .content("{\"amount_minor\":1500,\"currency\":\"KES\"}"))
                .andExpect(status().isCreated());

        assertEquals(1, env.upstream.forwardCount());
        var forwarded = env.upstream.forwardedRequests().get(0);
        assertEquals("POST", forwarded.method());
        assertEquals("/v1/payments", forwarded.path());
        assertEquals("{\"amount_minor\":1500,\"currency\":\"KES\"}", forwarded.body());
        assertEquals(key.key().principalId(), forwarded.principalId());
    }

    @Test
    void queryStringsTravelAlongAndGetsRelayToo() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedFullKey(env.newPrincipal());
        env.upstream.respondWith(request -> new UpstreamResponse(200, "{\"page\":[]}"));

        mvc.perform(get("/v1/payments?limit=20&cursor=abc")
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk());

        var forwarded = env.upstream.forwardedRequests().get(0);
        assertEquals("GET", forwarded.method());
        assertEquals("/v1/payments?limit=20&cursor=abc", forwarded.path());
        assertEquals(null, forwarded.body());
    }

    @Test
    void idempotentReplaysReturnTheStoredUpstreamResponse() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedFullKey(env.newPrincipal());
        int[] calls = {0};
        env.upstream.respondWith(request -> {
            calls[0]++;
            return new UpstreamResponse(201, "{\"payment_id\":\"pay_" + calls[0] + "\"}");
        });

        mvc.perform(post("/v1/payments")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-passthrough-2")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("X-Idempotent-Replay"));

        mvc.perform(post("/v1/payments")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-passthrough-2")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"));

        // the upstream saw exactly one forward
        assertEquals(1, env.upstream.forwardCount());
        // and the replay served the ORIGINAL body (pay_1, not pay_2)
        String body = mvc.perform(post("/v1/payments")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-passthrough-2")
                        .contentType("application/json").content("{}"))
                .andReturn().getResponse().getContentAsString();
        assertEquals("{\"payment_id\":\"pay_1\"}", body);
    }

    @Test
    void theSameIdempotencyKeyWithADifferentPayloadIs409() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedFullKey(env.newPrincipal());
        env.upstream.respondWith(request -> new UpstreamResponse(201, "{}"));
        mvc.perform(post("/v1/payouts")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-passthrough-3")
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/v1/payouts")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "idem-passthrough-3")
                        .contentType("application/json").content("{\"a\":2}"))
                .andExpect(status().isConflict());
    }

    @Test
    void everyRoutedClassReachesTheUpstreamWithItsOwnScope() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedFullKey(env.newPrincipal());
        env.upstream.respondWith(request -> new UpstreamResponse(200, "{\"ok\":true}"));
        for (String path : new String[]{"/v1/payments", "/v1/payouts", "/v1/transfers",
                "/v1/wallets", "/v1/fx/quotes"}) {
            mvc.perform(get(path).header("Authorization", key.authorization()))
                    .andExpect(status().isOk());
        }
        assertEquals(5, env.upstream.forwardCount());
    }

    @Test
    void upstreamErrorsRelayVerbatimIncluding422And5xx() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedFullKey(env.newPrincipal());
        env.upstream.respondWith(request -> new UpstreamResponse(422,
                "{\"error\":{\"code\":\"insufficient_funds\"}}"));
        mvc.perform(get("/v1/wallets")
                        .header("Authorization", key.authorization()))
                .andExpect(status().isUnprocessableContent());

        env.upstream.respondWith(request -> new UpstreamResponse(503, "{\"error\":true}"));
        mvc.perform(get("/v1/fx/quotes")
                        .header("Authorization", key.authorization()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void writeScopedRoutesRequireWriteScopesAtTheFilter() throws Exception {
        // read-only key: GETs relay, POSTs are 403 before any forward
        GatewayTestEnv.SeededKey readKey = env.seedKey(env.newPrincipal(),
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.PAYMENTS_READ,
                        com.sharkpay.gateway.domain.Scope.WALLETS_READ,
                        com.sharkpay.gateway.domain.Scope.FX_READ), 300, 2_000_000L);
        env.upstream.respondWith(request -> new UpstreamResponse(200, "{\"ok\":true}"));

        mvc.perform(get("/v1/payments").header("Authorization", readKey.authorization()))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/payments")
                        .header("Authorization", readKey.authorization())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        assertEquals(1, env.upstream.forwardCount());
    }
}
