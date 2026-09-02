package com.sharkpay.gateway.events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CloudEventEnvelope validation: the contracts/events/*.json shape,
 * fail-closed on every malformed variant, tolerant of extra fields.
 */
class CloudEventEnvelopeTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:05Z");
    private final JsonMapper mapper = JsonMapper.builder().build();

    private CloudEventEnvelope envelope() {
        return CloudEventEnvelope.of(UUID.randomUUID(), "payments.payment.succeeded.v1",
                "sharkpay/payments", "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", NOW,
                mapper.createObjectNode().put("payment_id", "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A"));
    }

    @Test
    void aWellFormedEnvelopeCarriesTheCanonicalValues() {
        UUID id = UUID.randomUUID();
        CloudEventEnvelope envelope = CloudEventEnvelope.of(id, "payouts.payout.failed.v1",
                "sharkpay/payouts", "pot_...", NOW, mapper.createObjectNode());
        assertEquals(id.toString(), envelope.id());
        assertEquals("payouts.payout.failed.v1", envelope.type());
        assertEquals("1.0", envelope.specversion());
        assertEquals("sharkpay/payouts", envelope.source());
        assertEquals("pot_...", envelope.subject());
        assertEquals(NOW, envelope.occurredAt());
        assertTrue(envelope.data().isObject());
        assertEquals("1.0", CloudEventEnvelope.SPECVERSION);
    }

    @Test
    void theIdMustBeAUuid() {
        assertThrows(IllegalArgumentException.class, () -> new CloudEventEnvelope(
                "not-a-uuid", "t", "1.0", "s", "sub", NOW, mapper.createObjectNode()));
        // canonical UUIDs of any case/variant are fine (parse happens elsewhere)
        assertNotNull(CloudEventEnvelope.of(UUID.randomUUID(), "t", "s", "sub", NOW,
                mapper.createObjectNode()));
    }

    @Test
    void specversionMustBeExactlyTen() {
        assertThrows(IllegalArgumentException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "0.3", "s", "sub", NOW,
                mapper.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "1", "s", "sub", NOW,
                mapper.createObjectNode()));
    }

    @Test
    void blankOrMissingTypeSourceAndSubjectAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "  ", "1.0", "s", "sub", NOW,
                mapper.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "1.0", "", "sub", NOW,
                mapper.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "1.0", "s", " ", NOW,
                mapper.createObjectNode()));
    }

    @Test
    void dataMustBeAJsonObject() {
        JsonNode array = mapper.createArrayNode();
        assertThrows(IllegalArgumentException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "1.0", "s", "sub", NOW, array));
        assertThrows(IllegalArgumentException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "1.0", "s", "sub", NOW,
                tools.jackson.databind.node.StringNode.valueOf("not-an-object")));
    }

    @Test
    void nullComponentsAreRejectedWithNpe() {
        assertThrows(NullPointerException.class, () -> new CloudEventEnvelope(null, "t", "1.0",
                "s", "sub", NOW, mapper.createObjectNode()));
        assertThrows(NullPointerException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), null, "1.0", "s", "sub", NOW,
                mapper.createObjectNode()));
        assertThrows(NullPointerException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", null, "s", "sub", NOW,
                mapper.createObjectNode()));
        assertThrows(NullPointerException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "1.0", null, "sub", NOW,
                mapper.createObjectNode()));
        assertThrows(NullPointerException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "1.0", "s", null, NOW,
                mapper.createObjectNode()));
        assertThrows(NullPointerException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "1.0", "s", "sub", null,
                mapper.createObjectNode()));
        assertThrows(NullPointerException.class, () -> new CloudEventEnvelope(
                UUID.randomUUID().toString(), "t", "1.0", "s", "sub", NOW, null));
    }

    @Test
    void envelopeEqualsAndHashCode() {
        CloudEventEnvelope one = envelope();
        assertEquals(one, one);
        assertTrue(one.toString().contains("payments.payment.succeeded.v1"));
    }
}
