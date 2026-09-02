package com.sharkpay.gateway.events;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Envelope codec: parses inbound CloudEvent JSON into
 * {@link CloudEventEnvelope} and renders the deterministic outbound webhook
 * payload (fixed field order — the exact bytes the HMAC signature covers).
 *
 * <p>Field names are snake_case exactly as in the schemas; parsing is
 * strict about shape (fail-closed 400s on malformed envelopes) but leaves
 * topic-catalog resolution to the caller.</p>
 */
public final class EnvelopeCodec {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_INSTANT;

    private final JsonMapper mapper;

    public EnvelopeCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parses and validates an inbound envelope JSON object.
     *
     * @throws IllegalArgumentException on any malformed shape (extra fields
     *         are tolerated — the internal envelope may carry optional
     *         fields; missing or mistyped ones are not)
     */
    public CloudEventEnvelope parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("event envelope must be a JSON object");
        }
        String id = requireString(node, "id");
        String type = requireString(node, "type");
        String specversion = requireString(node, "specversion");
        String source = requireString(node, "source");
        String subject = requireString(node, "subject");
        String occurredAt = requireString(node, "occurred_at");
        JsonNode data = node.get("data");
        if (data == null || data.isNull() || !data.isObject()) {
            throw new IllegalArgumentException("event data must be a JSON object");
        }
        Instant occurred;
        try {
            occurred = Instant.parse(occurredAt);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("occurred_at must be RFC 3339: " + occurredAt);
        }
        return new CloudEventEnvelope(id, type, specversion, source, subject, occurred, data);
    }

    /**
     * The exact payload bytes POSTed to webhook endpoints: the inbound
     * envelope with the internal topic type swapped for the unversioned
     * catalog name, fields in fixed order ({@code id, type, specversion,
     * source, subject, occurred_at, data}) — byte-identical across calls,
     * which is what makes the HMAC signature verifiable.
     */
    public String outboundPayload(CloudEventEnvelope envelope, String publicEventType) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("id", envelope.id());
        outbound.put("type", publicEventType);
        outbound.put("specversion", CloudEventEnvelope.SPECVERSION);
        outbound.put("source", envelope.source());
        outbound.put("subject", envelope.subject());
        outbound.put("occurred_at", ISO.format(envelope.occurredAt()));
        outbound.set("data", envelope.data());
        return mapper.writeValueAsString(outbound);
    }

    /** Builds an envelope data object for a map-shaped payload (sandbox). */
    public ObjectNode newDataObject() {
        return mapper.createObjectNode();
    }

    private static String requireString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isString()) {
            throw new IllegalArgumentException("event field '" + field + "' must be a string");
        }
        String text = value.asString();
        if (text.isBlank()) {
            throw new IllegalArgumentException("event field '" + field + "' must not be blank");
        }
        return text;
    }
}
