package com.sharkpay.gateway.events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EnvelopeCodec: strict inbound parsing (fail-closed 400 shapes) and the
 * deterministic outbound payload — fixed field order, byte-identical across
 * calls, which is what makes the HMAC signature verifiable by receivers.
 */
class EnvelopeCodecTest {

    private final EnvelopeCodec codec = new EnvelopeCodec(JsonMapper.builder().build());
    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode validEnvelopeJson() {
        return mapper.readTree("""
                {
                  "id": "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                  "type": "payments.payment.succeeded.v1",
                  "specversion": "1.0",
                  "source": "sharkpay/payments",
                  "subject": "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
                  "occurred_at": "2026-09-01T10:00:05Z",
                  "data": {"payment_id": "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", "state": "SUCCEEDED"}
                }
                """);
    }

    @Test
    void parsesAWellFormedEnvelope() {
        CloudEventEnvelope envelope = codec.parse(validEnvelopeJson());
        assertEquals("0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d", envelope.id());
        assertEquals("payments.payment.succeeded.v1", envelope.type());
        assertEquals("sharkpay/payments", envelope.source());
        assertEquals(Instant.parse("2026-09-01T10:00:05Z"), envelope.occurredAt());
        assertEquals("SUCCEEDED", envelope.data().get("state").asString());
    }

    @Test
    void extraEnvelopeFieldsAreTolerated() {
        JsonNode withExtras = validEnvelopeJson().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) withExtras).put("traceparent", "00-x-y-01");
        assertEquals("payments.payment.succeeded.v1", codec.parse(withExtras).type());
    }

    @Test
    void nonObjectInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.parse(null));
        assertThrows(IllegalArgumentException.class, () -> codec.parse(
                mapper.createArrayNode()));
        assertThrows(IllegalArgumentException.class, () -> codec.parse(
                mapper.createObjectNode().put("scalar", true)));
    }

    @Test
    void missingRequiredFieldsAreRejected() {
        for (String field : new String[]{"id", "type", "specversion", "source", "subject",
                "occurred_at", "data"}) {
            tools.jackson.databind.node.ObjectNode json = (tools.jackson.databind.node.ObjectNode)
                    validEnvelopeJson().deepCopy();
            json.remove(field);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> codec.parse(json), "missing " + field + " must be rejected");
            assertTrue(error.getMessage().contains(field) || error.getMessage().contains("data"));
        }
    }

    @Test
    void nonStringFieldsAreRejected() {
        tools.jackson.databind.node.ObjectNode json = (tools.jackson.databind.node.ObjectNode)
                validEnvelopeJson().deepCopy();
        json.put("type", 42);
        assertThrows(IllegalArgumentException.class, () -> codec.parse(json));

        tools.jackson.databind.node.ObjectNode nullType =
                (tools.jackson.databind.node.ObjectNode) validEnvelopeJson().deepCopy();
        nullType.putNull("subject");
        assertThrows(IllegalArgumentException.class, () -> codec.parse(nullType));
    }

    @Test
    void blankStringFieldsAreRejected() {
        tools.jackson.databind.node.ObjectNode json = (tools.jackson.databind.node.ObjectNode)
                validEnvelopeJson().deepCopy();
        json.put("source", "   ");
        assertThrows(IllegalArgumentException.class, () -> codec.parse(json));
    }

    @Test
    void nonObjectDataIsRejected() {
        tools.jackson.databind.node.ObjectNode json = (tools.jackson.databind.node.ObjectNode)
                validEnvelopeJson().deepCopy();
        json.put("data", "not-an-object");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> codec.parse(json));
        assertTrue(error.getMessage().contains("data"));
    }

    @Test
    void occurredAtMustBeRfc3339() {
        tools.jackson.databind.node.ObjectNode json = (tools.jackson.databind.node.ObjectNode)
                validEnvelopeJson().deepCopy();
        json.put("occurred_at", "not a date");
        assertThrows(IllegalArgumentException.class, () -> codec.parse(json));

        tools.jackson.databind.node.ObjectNode offset = (tools.jackson.databind.node.ObjectNode)
                validEnvelopeJson().deepCopy();
        offset.put("occurred_at", "2026-09-01T13:00:05+03:00");
        assertEquals(Instant.parse("2026-09-01T10:00:05Z"), codec.parse(offset).occurredAt());
    }

    @Test
    void outboundPayloadHasFixedFieldOrderAndThePublicType() {
        CloudEventEnvelope envelope = codec.parse(validEnvelopeJson());
        String payload = codec.outboundPayload(envelope, "payment.succeeded");

        assertEquals("{\"id\":\"0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d\","
                + "\"type\":\"payment.succeeded\",\"specversion\":\"1.0\","
                + "\"source\":\"sharkpay/payments\","
                + "\"subject\":\"pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A\","
                + "\"occurred_at\":\"2026-09-01T10:00:05Z\","
                + "\"data\":{\"payment_id\":\"pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A\","
                + "\"state\":\"SUCCEEDED\"}}", payload);
        // the internal versioned topic never leaks to webhook consumers
        assertTrue(payload.contains("\"type\":\"payment.succeeded\""));
        assertNotEquals(payload.indexOf("\"id\""), -1);
    }

    @Test
    void outboundPayloadIsByteIdenticalAcrossCalls() {
        CloudEventEnvelope envelope = codec.parse(validEnvelopeJson());
        assertEquals(codec.outboundPayload(envelope, "payment.succeeded"),
                codec.outboundPayload(envelope, "payment.succeeded"));
        // and distinct when the public type differs
        assertNotEquals(codec.outboundPayload(envelope, "payment.succeeded"),
                codec.outboundPayload(envelope, "payment.failed"));
    }

    @Test
    void newDataObjectIsEmptyAndMutable() {
        assertTrue(codec.newDataObject().isEmpty());
        codec.newDataObject().put("k", "v");
        // each call returns a fresh node
        assertTrue(codec.newDataObject().isEmpty());
    }
}
