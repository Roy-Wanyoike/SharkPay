package com.sharkpay.wallet.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Hold lifecycle: {@code active → released} (funds returned to available) or
 * {@code active → captured} (settled debit; a partial capture releases the
 * remainder immediately). Terminal states are final.
 */
public enum HoldState {

    ACTIVE("active"),
    RELEASED("released"),
    CAPTURED("captured");

    private final String wireName;

    HoldState(String wireName) {
        this.wireName = wireName;
    }

    /** The contract wire name (lowercase). */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static HoldState fromWire(String value) {
        for (HoldState state : values()) {
            if (state.wireName.equalsIgnoreCase(value == null ? "" : value.trim())) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown hold state: " + value);
    }
}
