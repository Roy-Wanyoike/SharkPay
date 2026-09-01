package com.sharkpay.risk.domain;

import java.util.Locale;
import java.util.UUID;

/**
 * Case id helpers. The storage key is a UUID; the externally visible id is
 * {@code case_<uuid-as-32-hex>} which matches the {@code risk.v1.json}
 * contract pattern {@code ^case_[0-9A-Za-z]{20,}$}. Lookups accept both
 * forms.
 */
public final class CaseIds {

    public static final String PREFIX = "case_";

    private CaseIds() {
    }

    /** Public case id: {@code case_} + the UUID's 32 hex digits. */
    public static String publicId(UUID id) {
        return PREFIX + id.toString().replace("-", "");
    }

    /**
     * Parses a case id in any accepted form ({@code case_<hex32>}, dashed
     * UUID, bare 32-hex UUID).
     *
     * @throws com.sharkpay.risk.domain.exceptions.InvalidCaseIdException when unparseable
     */
    public static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new com.sharkpay.risk.domain.exceptions.InvalidCaseIdException(String.valueOf(raw));
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith(PREFIX)) {
            trimmed = trimmed.substring(PREFIX.length());
        }
        String hex = trimmed.replace("-", "");
        if (!hex.matches("[0-9a-f]{32}")) {
            throw new com.sharkpay.risk.domain.exceptions.InvalidCaseIdException(raw);
        }
        String dashed = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
        return UUID.fromString(dashed);
    }
}
