package com.sharkpay.identity.ports.event;

import com.sharkpay.identity.domain.exception.ValidationException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * CloudEvents 1.0 envelope for events published by the identity service.
 *
 * <p>The wire format (contracts/events/identity.v1.json) adds
 * {@code subject} (the SharkId of the affected principal) and serializes
 * {@code time} as {@code occurred_at}; the transport adapter performs that
 * mapping at integration time.</p>
 */
public record CloudEvent(
        String specversion,
        String type,
        String source,
        String id,
        OffsetDateTime time,
        Map<String, Object> data) {

    public static final String SPECVERSION = "1.0";

    public CloudEvent {
        if (!SPECVERSION.equals(specversion)) {
            throw new ValidationException("INVALID_EVENT", "specversion must be '1.0'");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(time, "time must not be null");
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static CloudEvent of(String type, String source, String id, OffsetDateTime time, Map<String, Object> data) {
        return new CloudEvent(SPECVERSION, type, source, id, time, data);
    }
}
