package com.sharkpay.gateway.api;

import com.sharkpay.gateway.domain.DeliveryState;
import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.testsupport.GatewayTestEnv;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook endpoint management per contracts/openapi/v1/webhooks.yaml
 * (create/list/get/delete, required scope webhooks:manage) plus the
 * gateway's delivery-log extensions: pause/resume, list deliveries,
 * replay of dead deliveries only.
 */
class WebhookEndpointControllerTest {

    private final GatewayTestEnv env = new GatewayTestEnv();
    private final MockMvc mvc = env.mockMvc();

    private static final String CREATE_BODY = """
            {"url": "https://merchant.example.com/sharkpay/webhooks",
             "events": ["payment.created", "payment.succeeded"],
             "secret": "whsec_5f8a2b9c1d4e6f7a8b9c0d1e2f3a4b5c"}
            """;

    private GatewayTestEnv.SeededKey key() {
        return env.seedFullKey(env.newPrincipal());
    }

    private String createEndpoint(GatewayTestEnv.SeededKey key, String idempotencyKey)
            throws Exception {
        return mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.url").value("https://merchant.example.com/sharkpay/webhooks"))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.state").value("active"))
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createReturnsTheSecretOnceAndReplaysAreRedacted() throws Exception {
        GatewayTestEnv.SeededKey key = key();
        String first = createEndpoint(key, "wh-idem-1");
        assertTrue(first.contains("whsec_5f8a2b9c1d4e6f7a8b9c0d1e2f3a4b5c"));
        // contract-exact shape: webhooks.yaml WebhookEndpoint declares
        // additionalProperties:false — exactly these seven fields
        tools.jackson.databind.JsonNode node = new tools.jackson.databind.json.JsonMapper()
                .readTree(first);
        java.util.Set<String> fields = new java.util.HashSet<>();
        node.propertyNames().forEach(fields::add);
        assertEquals(java.util.Set.of("id", "url", "events", "state", "secret", "created_at",
                "updated_at"), fields);

        String replay = mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "wh-idem-1")
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.secret").value("whsec_redacted"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(replay.contains("whsec_5f8a2b9c1d4e6f7a8b9c0d1e2f3a4b5c"));

        // same idempotency key + different payload → 409
        mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "wh-idem-1")
                        .contentType("application/json")
                        .content("{\"url\": \"https://other.example.com/h\", "
                                + "\"events\": [\"payout.*\"], \"secret\": "
                                + "\"whsec_0123456789abcdef\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
    }

    @Test
    void httpUrlsAre422AndInvalidEventsAre422() throws Exception {
        GatewayTestEnv.SeededKey key = key();
        mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "wh-idem-2")
                        .contentType("application/json")
                        .content("{\"url\": \"http://merchant.example.com/insecure\", "
                                + "\"events\": [\"payment.succeeded\"], "
                                + "\"secret\": \"whsec_0123456789abcdef\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("http_url_required"));

        mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "wh-idem-3")
                        .contentType("application/json")
                        .content("{\"url\": \"https://merchant.example.com/h\", "
                                + "\"events\": [\"not.a.catalog.event\"], "
                                + "\"secret\": \"whsec_0123456789abcdef\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("invalid_events"));

        // missing Idempotency-Key → 400 with the header named in details
        mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.header").value("Idempotency-Key"));

        // secret length validation (jakarta validation → 400)
        mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "wh-idem-4")
                        .contentType("application/json")
                        .content("{\"url\": \"https://m.example.com/h\", "
                                + "\"events\": [\"payment.succeeded\"], \"secret\": \"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAndGetAndDeleteFollowTheContract() throws Exception {
        GatewayTestEnv.SeededKey key = key();
        String endpointId = readId(createEndpoint(key, "wh-idem-5"));

        mvc.perform(get("/v1/webhook-endpoints").header("Authorization", key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());

        mvc.perform(get("/v1/webhook-endpoints/{id}", endpointId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(endpointId))
                .andExpect(jsonPath("$.secret").value("whsec_redacted"));

        mvc.perform(delete("/v1/webhook-endpoints/{id}", endpointId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isNoContent());

        // gone: 404 for get, list empty
        mvc.perform(get("/v1/webhook-endpoints/{id}", endpointId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        mvc.perform(get("/v1/webhook-endpoints").header("Authorization", key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        // foreign endpoint is indistinguishable from a missing one
        mvc.perform(get("/v1/webhook-endpoints/{id}", "wh_missing00000000000000000")
                        .header("Authorization", key.authorization()))
                .andExpect(status().isNotFound());
    }

    @Test
    void pauseAndResumeToggleDeliveries() throws Exception {
        GatewayTestEnv.SeededKey key = key();
        String endpointId = readId(createEndpoint(key, "wh-idem-6"));

        mvc.perform(post("/v1/webhook-endpoints/{id}/pause", endpointId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/webhook-endpoints/{id}", endpointId)
                        .header("Authorization", key.authorization()))
                .andExpect(jsonPath("$.state").value("paused"));
        // paused: no deliveries are dispatched for matching events
        env.feed.paymentSucceeded("pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", 1500, "KES");
        assertTrue(env.deliveries.all().isEmpty());

        mvc.perform(post("/v1/webhook-endpoints/{id}/resume", endpointId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/webhook-endpoints/{id}", endpointId)
                        .header("Authorization", key.authorization()))
                .andExpect(jsonPath("$.state").value("active"));
        env.feed.paymentSucceeded("pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", 1500, "KES");
        assertEquals(1, env.deliveries.all().size());
    }

    @Test
    void deliveriesAreListedAndOnlyDeadOnesReplay() throws Exception {
        GatewayTestEnv.SeededKey key = key();
        String endpointId = readId(createEndpoint(key, "wh-idem-7"));

        // one delivered delivery
        env.feed.paymentSucceeded("pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", 1500, "KES");
        env.sweep();
        // one dead delivery (fail 8 times)
        env.sender.alwaysReject(500);
        env.feed.paymentSucceeded("pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0B", 1500, "KES");
        for (int i = 0; i < 8; i++) {
            env.clock.set(env.deliveries.all().values().stream()
                    .filter(d -> d.state() == DeliveryState.PENDING)
                    .findFirst().orElseThrow().nextAttemptAt());
            env.sweep();
        }

        mvc.perform(get("/v1/webhook-endpoints/{id}/deliveries", endpointId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        // find the ids
        String deadId = env.deliveries.all().values().stream()
                .filter(d -> d.state() == DeliveryState.DEAD).findFirst().orElseThrow().id();
        String deliveredId = env.deliveries.all().values().stream()
                .filter(d -> d.state() == DeliveryState.DELIVERED).findFirst().orElseThrow().id();

        // delivered deliveries cannot be replayed: 409
        mvc.perform(post("/v1/webhook-endpoints/{id}/deliveries/{deliveryId}/replay",
                        endpointId, deliveredId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"));

        // dead deliveries replay: 202, back to pending
        mvc.perform(post("/v1/webhook-endpoints/{id}/deliveries/{deliveryId}/replay",
                        endpointId, deadId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.delivery_id").value(deadId))
                .andExpect(jsonPath("$.state").value("pending"));

        // and the replayed delivery now sends again
        env.sender.alwaysDeliver(200);
        env.clock.advance(Duration.ofMinutes(1));
        env.sweep();
        WebhookDelivery replayed = env.deliveries.findById(deadId).orElseThrow();
        assertEquals(DeliveryState.DELIVERED, replayed.state());
        assertEquals(1, replayed.attemptCount());

        // foreign endpoints 404 on the delivery log
        mvc.perform(get("/v1/webhook-endpoints/{id}/deliveries", "wh_missing00000000000000000")
                        .header("Authorization", key.authorization()))
                .andExpect(status().isNotFound());
    }

    @Test
    void paginationIsBoundedAndCursorFollows() throws Exception {
        GatewayTestEnv.SeededKey key = key();
        createEndpoint(key, "wh-idem-8");
        String second = readId(mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "wh-idem-9")
                        .contentType("application/json")
                        .content("{\"url\": \"https://other.example.com/h\", "
                                + "\"events\": [\"payout.*\"], "
                                + "\"secret\": \"whsec_0123456789abcdef\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mvc.perform(get("/v1/webhook-endpoints?limit=1").header("Authorization",
                        key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.next_cursor").isNotEmpty());

        mvc.perform(get("/v1/webhook-endpoints?limit=0").header("Authorization",
                        key.authorization()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/webhook-endpoints?limit=101").header("Authorization",
                        key.authorization()))
                .andExpect(status().isBadRequest());

        // cursor pages to the remaining endpoint
        mvc.perform(get("/v1/webhook-endpoints?limit=1").queryParam("cursor", "zzz")
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk());
        assertTrue(second.startsWith("wh_"));
    }

    @Test
    void globPatternsAreAcceptedAndStored() throws Exception {
        GatewayTestEnv.SeededKey key = key();
        String body = mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "wh-idem-10")
                        .contentType("application/json")
                        .content("{\"url\": \"https://merchant.example.com/h\", "
                                + "\"events\": [\"payment.*\", \"payout.created\"], "
                                + "\"secret\": \"whsec_0123456789abcdef\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.events[0]").value("payment.*"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("payout.created"));
    }

    private static String readId(String body) {
        int index = body.indexOf("\"id\":\"");
        assertTrue(index >= 0, body);
        int start = index + "\"id\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
