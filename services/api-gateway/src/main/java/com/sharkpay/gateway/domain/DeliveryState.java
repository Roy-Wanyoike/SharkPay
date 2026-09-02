package com.sharkpay.gateway.domain;

/**
 * Webhook delivery lifecycle (mission: pending → delivered | dead).
 * A {@code dead} delivery exhausted its {@value BackoffPolicy#MAX_ATTEMPTS}
 * attempts and can only be revived through the operator replay endpoint.
 */
public enum DeliveryState {

    PENDING("pending"),
    DELIVERED("delivered"),
    DEAD("dead");

    private final String wireName;

    DeliveryState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static DeliveryState fromWire(String wireName) {
        for (DeliveryState state : values()) {
            if (state.wireName.equals(wireName)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown delivery state: " + wireName);
    }
}
