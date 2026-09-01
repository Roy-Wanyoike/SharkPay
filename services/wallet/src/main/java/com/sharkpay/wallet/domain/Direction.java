package com.sharkpay.wallet.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Posting direction of a ledger journal leg: a debit decreases the wallet
 * balance, a credit increases it (docs/DATA-MODEL.md §3.1).
 */
public enum Direction {

    DEBIT("debit"),
    CREDIT("credit");

    private final String wireName;

    Direction(String wireName) {
        this.wireName = wireName;
    }

    /** The contract wire name (lowercase, wallets.yaml StatementEntry.direction). */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static Direction fromWire(String value) {
        for (Direction direction : values()) {
            if (direction.wireName.equalsIgnoreCase(value == null ? "" : value.trim())) {
                return direction;
            }
        }
        throw new IllegalArgumentException("unknown posting direction: " + value);
    }
}
