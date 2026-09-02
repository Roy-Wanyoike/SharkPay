package com.sharkpay.gateway.api;

import com.sharkpay.gateway.domain.DeliveryState;
import com.sharkpay.gateway.testsupport.GatewayTestEnv;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /internal/events — the dev/integration event intake that feeds the
 * same dispatcher the real NATS/Kafka binding will call: 202 for registered
 * topics, 400 for malformed envelopes, 422 for unregistered topics, and
 * at-least-once intake dedupe via delivery idempotency.
 */
class InternalEventControllerTest {

    private final GatewayTestEnv env = new GatewayTestEnv();
    private final MockMvc mvc = env.mockMvc();

    @Test
    void aWellFormedCatalogTopicIsAcceptedAndFannedOut() throws Exception {
        subscribeAll();
        mvc.perform(post("/internal/events").contentType("application/json").content("""
                {"id": "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                 "type": "payments.payment.succeeded.v1",
                 "specversion": "1.0",
                 "source": "sharkpay/payments",
                 "subject": "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
                 "occurred_at": "2026-09-01T10:00:05Z",
                 "data": {"payment_id": "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "state": "SUCCEEDED"}}
                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.event_id").value("0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d"))
                .andExpect(jsonPath("$.type").value("payments.payment.succeeded.v1"))
                .andExpect(jsonPath("$.deliveries_created").value(1));
        assertEquals(1, env.deliveries.all().size());
    }

    @Test
    void malformedEnvelopesAre400() throws Exception {
        // not an object
        mvc.perform(post("/internal/events").contentType("application/json").content("[1,2]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        // missing fields
        mvc.perform(post("/internal/events").contentType("application/json")
                        .content("{\"id\": \"0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d\"}"))
                .andExpect(status().isBadRequest());
        // wrong specversion
        mvc.perform(post("/internal/events").contentType("application/json").content("""
                {"id": "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                 "type": "payments.payment.succeeded.v1", "specversion": "0.3",
                 "source": "sharkpay/payments", "subject": "pay_1",
                 "occurred_at": "2026-09-01T10:00:05Z", "data": {}}
                """))
                .andExpect(status().isBadRequest());
        // non-uuid id
        mvc.perform(post("/internal/events").contentType("application/json").content("""
                {"id": "not-a-uuid", "type": "payments.payment.succeeded.v1",
                 "specversion": "1.0", "source": "sharkpay/payments", "subject": "pay_1",
                 "occurred_at": "2026-09-01T10:00:05Z", "data": {}}
                """))
                .andExpect(status().isBadRequest());
        // malformed date
        mvc.perform(post("/internal/events").contentType("application/json").content("""
                {"id": "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                 "type": "payments.payment.succeeded.v1", "specversion": "1.0",
                 "source": "sharkpay/payments", "subject": "pay_1",
                 "occurred_at": "yesterday", "data": {}}
                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void topicsOutsideTheRegistryAre422() throws Exception {
        mvc.perform(post("/internal/events").contentType("application/json").content("""
                {"id": "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                 "type": "some.unknown.topic.v1", "specversion": "1.0",
                 "source": "sharkpay/payments", "subject": "pay_1",
                 "occurred_at": "2026-09-01T10:00:05Z", "data": {}}
                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("unknown_event_type"));
    }

    @Test
    void internalOnlyTopicsAreAcceptedButCreateNoDeliveries() throws Exception {
        subscribeAll();
        mvc.perform(post("/internal/events").contentType("application/json").content("""
                {"id": "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                 "type": "ledger.posting.committed.v1", "specversion": "1.0",
                 "source": "sharkpay/ledger", "subject": "ent_1",
                 "occurred_at": "2026-09-01T10:00:05Z", "data": {"entries": []}}
                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveries_created").value(0));
        assertEquals(0, env.deliveries.all().size());
    }

    @Test
    void duplicateEventIdsAreNoOpsDeliveryIdempotency() throws Exception {
        subscribeAll();
        String envelope = """
                {"id": "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                 "type": "payments.payment.created.v1", "specversion": "1.0",
                 "source": "sharkpay/payments", "subject": "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
                 "occurred_at": "2026-09-01T10:00:05Z",
                 "data": {"payment_id": "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A"}}
                """;
        mvc.perform(post("/internal/events").contentType("application/json").content(envelope))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveries_created").value(1));
        // the broker's redelivery of the same event id creates nothing
        mvc.perform(post("/internal/events").contentType("application/json").content(envelope))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveries_created").value(0));
        assertEquals(1, env.deliveries.all().size());
        // and it stays pending — nothing was delivered
        assertEquals(DeliveryState.PENDING, env.deliveries.all().values().iterator().next()
                .state());
    }

    private void subscribeAll() {
        env.createSubscription.create(env.newPrincipal(),
                "https://merchant.example.com/hooks", java.util.List.of("*"),
                "whsec_0123456789abcdef");
    }
}
