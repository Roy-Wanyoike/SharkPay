package com.sharkpay.risk.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * Contract vocabulary values: every domain enum that has a wire (JSON) form
 * implements this; {@link #parse} is the shared lenient parser (lowercase,
 * trimmed) used by the API DTOs and persistence mappers.
 */
public interface WireValue {

    String wire();

    static <E extends Enum<E> & WireValue> E parse(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (E value : type.getEnumConstants()) {
            if (value.wire().equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException(field + " must be one of " + Arrays.toString(type.getEnumConstants())
                + ", got '" + raw + "'");
    }
}
