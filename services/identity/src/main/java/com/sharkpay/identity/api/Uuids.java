package com.sharkpay.identity.api;

import com.sharkpay.identity.domain.exception.ValidationException;
import java.util.UUID;

/**
 * Parsing helpers for path variables.
 */
public final class Uuids {

    private Uuids() {
    }

    public static UUID parse(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("INVALID_UUID", field + " must not be blank");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_UUID",
                    field + " '" + raw + "' is not a valid UUID");
        }
    }
}
