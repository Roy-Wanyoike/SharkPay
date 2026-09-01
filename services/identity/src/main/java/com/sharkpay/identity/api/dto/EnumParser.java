package com.sharkpay.identity.api.dto;

import com.sharkpay.identity.domain.exception.ValidationException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Lenient enum parsing for request DTOs (accepts case-insensitive input,
 * normalizes to the domain enum). Unknown values produce a 400 with a
 * helpful message.
 */
public final class EnumParser {

    private EnumParser() {
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String raw, String errorCode) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(errorCode, type.getSimpleName() + " must not be blank");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(errorCode,
                    "unknown " + type.getSimpleName() + " '" + raw.trim()
                            + "' (expected one of " + Arrays.toString(type.getEnumConstants()) + ")");
        }
    }
}
