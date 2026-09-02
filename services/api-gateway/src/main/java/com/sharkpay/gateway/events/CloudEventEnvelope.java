package com.sharkpay.gateway.events;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CloudEvents 1.0-aligned event envelope as consumed by the gateway (the
 * contracts/events/*.json shape): {@code id, type, specversion, source,
 * subject, occurred_at, data}. Inbound {@code type} is the internal
 * versioned topic name; the outbound webhook payload swaps it for the
 * unversioned catalog name (events.md "Webhook mapping") — see
 * {@link EnvelopeCodec#outboundPayload}.
 *
 * <p>Validation is fail-closed: specversion must be {@code 1.0}, the id a
 * UUID, and {@code data} a JSON object. Consumers dedupe on {@code id}.</p>
 */
public record CloudEventEnvelope(String id, String type, String specversion, String source,
                                 String subject, Instant occurredAt, JsonNode data) {

    public static final String SPECVERSION = "1.0";

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public CloudEventEnvelope {
        Objects.requireNonNull(id, "event id is required");
        Objects.requireNonNull(type, "event type is required");
        Objects.requireNonNull(specversion, "specversion is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(subject, "subject is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(data, "data is required");
        if (!UUID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("event id must be a UUID: " + id);
        }
        if (type.isBlank()) {
            throw new IllegalArgumentException("event type must not be blank");
        }
        if (!SPECVERSION.equals(specversion)) {
            throw new IllegalArgumentException("specversion must be 1.0: " + specversion);
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (!data.isObject()) {
            throw new IllegalArgumentException("event data must be a JSON object");
        }
    }

    /** Builds a well-formed envelope (used by the sandbox and tests). */
    public static CloudEventEnvelope of(UUID id, String type, String source, String subject,
                                        Instant occurredAt, JsonNode data) {
        return new CloudEventEnvelope(id.toString(), type, SPECVERSION, source, subject,
                occurredAt, data);
    }
}
