package com.sharkpay.gateway.api;

import com.sharkpay.gateway.testsupport.GatewayTestEnv;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /sandbox simulated provider surface — clearly separated from /v1:
 * its own route class and scopes, in-memory state, one scripted step per
 * poll, one webhook event per step.
 */
class SandboxControllerTest {

    private final GatewayTestEnv env = new GatewayTestEnv();
    private final MockMvc mvc = env.mockMvc();

    private static final String CREATE_BODY = """
            {"amount_minor": 150000, "currency": "KES",
             "destination_wallet": "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "rail": "honeycoin"}
            """;

    @Test
    void createIs201AndTheScriptStartsAtCreated() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedKey(env.newPrincipal(),
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.PAYMENTS_WRITE), 300,
                2_000_000L);
        mvc.perform(post("/sandbox/payments")
                        .header("Authorization", key.authorization())
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.amount_minor").value(150000))
                .andExpect(jsonPath("$.currency").value("KES"))
                .andExpect(jsonPath("$.exponent").value(2))
                .andExpect(jsonPath("$.rail").value("honeycoin"));
    }

    @Test
    void pollsAdvanceOneStepAtATimeThroughSucceeded() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedKey(env.newPrincipal(),
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.PAYMENTS_WRITE,
                        com.sharkpay.gateway.domain.Scope.PAYMENTS_READ), 300, 2_000_000L);
        String paymentId = readId(mvc.perform(post("/sandbox/payments")
                        .header("Authorization", key.authorization())
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mvc.perform(get("/sandbox/payments/{id}", paymentId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING_PROVIDER"));
        mvc.perform(get("/sandbox/payments/{id}", paymentId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));
        // terminal polls are stable
        mvc.perform(get("/sandbox/payments/{id}", paymentId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));
    }

    @Test
    void sandboxEventsFanOutToWebhookSubscriptions() throws Exception {
        // a merchant key with both sandbox + webhook scopes
        GatewayTestEnv.SeededKey key = env.seedKey(env.newPrincipal(),
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.PAYMENTS_WRITE,
                        com.sharkpay.gateway.domain.Scope.PAYMENTS_READ,
                        com.sharkpay.gateway.domain.Scope.WEBHOOKS_MANAGE), 300, 2_000_000L);
        mvc.perform(post("/v1/webhook-endpoints")
                        .header("Authorization", key.authorization())
                        .header("Idempotency-Key", "sbx-1")
                        .contentType("application/json")
                        .content("{\"url\": \"https://merchant.example.com/h\", "
                                + "\"events\": [\"payment.*\"], "
                                + "\"secret\": \"whsec_0123456789abcdef\"}"))
                .andExpect(status().isCreated());

        String paymentId = readId(mvc.perform(post("/sandbox/payments")
                        .header("Authorization", key.authorization())
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get("/sandbox/payments/{id}", paymentId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk());
        mvc.perform(get("/sandbox/payments/{id}", paymentId)
                        .header("Authorization", key.authorization()))
                .andExpect(status().isOk());

        // created + pending_provider + succeeded: three signed deliveries
        assertEquals(3, env.deliveries.all().size());
        env.sweep();
        assertEquals(3, env.sender.sendCount());
        String payload = env.sender.sends().get(0).bodyText();
        org.junit.jupiter.api.Assertions.assertTrue(
                payload.contains("\"source\":\"sharkpay/sandbox\""), payload);
    }

    @Test
    void validationAndUnknownIdsMapTo400And404() throws Exception {
        GatewayTestEnv.SeededKey key = env.seedKey(env.newPrincipal(),
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.PAYMENTS_WRITE,
                        com.sharkpay.gateway.domain.Scope.PAYMENTS_READ), 300, 2_000_000L);
        // zero amount (jakarta validation) → 400
        mvc.perform(post("/sandbox/payments")
                        .header("Authorization", key.authorization())
                        .contentType("application/json")
                        .content("{\"amount_minor\": 0, \"currency\": \"KES\", "
                                + "\"destination_wallet\": \"wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A\", "
                                + "\"rail\": \"bank\"}"))
                .andExpect(status().isBadRequest());
        // unknown currency reaches the domain → 400
        mvc.perform(post("/sandbox/payments")
                        .header("Authorization", key.authorization())
                        .contentType("application/json")
                        .content("{\"amount_minor\": 1, \"currency\": \"JPY\", "
                                + "\"destination_wallet\": \"wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A\", "
                                + "\"rail\": \"bank\"}"))
                .andExpect(status().isBadRequest());
        // missing body field → 400
        mvc.perform(post("/sandbox/payments")
                        .header("Authorization", key.authorization())
                        .contentType("application/json")
                        .content("{\"amount_minor\": 1, \"currency\": \"KES\"}"))
                .andExpect(status().isBadRequest());
        // unknown payment → 404
        mvc.perform(get("/sandbox/payments/{id}", "pay_missing0000000000000000000")
                        .header("Authorization", key.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void sandboxRequiresPaymentScopesButIsReachableByFullKeys() throws Exception {
        // a webhooks-only key cannot create sandbox payments: 403
        GatewayTestEnv.SeededKey webhookOnly = env.seedKey(env.newPrincipal(),
                java.util.Set.of(com.sharkpay.gateway.domain.Scope.WEBHOOKS_MANAGE), 300,
                2_000_000L);
        mvc.perform(post("/sandbox/payments")
                        .header("Authorization", webhookOnly.authorization())
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isForbidden());
    }

    private static String readId(String body) {
        int index = body.indexOf("\"id\":\"");
        org.junit.jupiter.api.Assertions.assertTrue(index >= 0, body);
        int start = index + "\"id\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
