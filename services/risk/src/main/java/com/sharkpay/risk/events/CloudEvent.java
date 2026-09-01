package com.sharkpay.risk.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * CloudEvents 1.0-aligned envelope exactly matching contracts/events
 * risk.v1.json: {@code {id, type, specversion, source, subject, occurred_at,
 * data}} with {@code additionalProperties: false}. Note the contract spells
 * the timestamp {@code occurred_at} (not CloudEvents' {@code time}) and
 * requires {@code subject} — the contract wins over the generic spec field
 * names.
 */
public record CloudEvent(
        String id,
        String type,
        String source,
        String specversion,
        String subject,
        Instant occurredAt,
        Map<String, Object> data) {

    public static final String SPECVERSION = "1.0";

    public CloudEvent {
        requireText(id, "id");
        requireText(type, "type");
        requireText(source, "source");
        requireText(subject, "subject");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!SPECVERSION.equals(specversion)) {
            throw new IllegalArgumentException("specversion must be '1.0', got '" + specversion + "'");
        }
        data = (data == null) ? Map.of() : Map.copyOf(data);
    }

    /**
     * New event of the given type/subject with a fresh UUID v7 id and the
     * risk service source.
     */
    public static CloudEvent of(String type, String subject, Instant occurredAt, Map<String, Object> data) {
        return new CloudEvent(UuidV7.next().toString(), type, RiskEventTypes.SOURCE, SPECVERSION,
                subject, occurredAt, data);
    }

    /** Wire map with the contract's exact field names (serde for Kafka/logs). */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("type", type);
        json.put("specversion", specversion);
        json.put("source", source);
        json.put("subject", subject);
        json.put("occurred_at", occurredAt.toString());
        json.put("data", data);
        return json;
    }

    private static void requireText(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
