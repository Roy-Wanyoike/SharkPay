package com.sharkpay.gateway.domain;

/**
 * Webhook endpoint lifecycle (contracts/openapi/v1/webhooks.yaml declares
 * {@code active}/{@code dead} for the endpoint; this service keeps the
 * operator-facing {@code paused} and the auto-paused {@code dead} distinct,
 * plus {@code deleted} for soft deletion — in-flight deliveries complete,
 * no new deliveries are created).
 */
public enum SubscriptionState {

    ACTIVE("active"),
    PAUSED("paused"),
    DEAD("dead"),
    DELETED("deleted");

    private final String wireName;

    SubscriptionState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static SubscriptionState fromWire(String wireName) {
        for (SubscriptionState state : values()) {
            if (state.wireName.equals(wireName)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown subscription state: " + wireName);
    }

    /** Whether new deliveries are created for this endpoint. */
    public boolean acceptsEvents() {
        return this == ACTIVE;
    }
}
