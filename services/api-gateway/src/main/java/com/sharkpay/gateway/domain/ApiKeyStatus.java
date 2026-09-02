package com.sharkpay.gateway.domain;

/**
 * API key lifecycle. Keys are SHA-256 hashed at rest; the plaintext
 * {@code sp_live_...} secret exists exactly once, in the creation response
 * (docs/SECURITY.md §2, docs/BACKEND-DESIGN.md §10).
 *
 * <ul>
 *   <li>{@code ACTIVE} — the current secret of the key;</li>
 *   <li>{@code ROTATING} — a superseded secret still accepted inside the
 *       rotation grace window (24 h, SECURITY §2); after grace expiry it is
 *       rejected like any unknown key;</li>
 *   <li>{@code REVOKED} — permanently dead.</li>
 * </ul>
 */
public enum ApiKeyStatus {

    ACTIVE("active"),
    ROTATING("rotating"),
    REVOKED("revoked");

    private final String wireName;

    ApiKeyStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ApiKeyStatus fromWire(String wireName) {
        for (ApiKeyStatus status : values()) {
            if (status.wireName.equals(wireName)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown api key status: " + wireName);
    }
}
