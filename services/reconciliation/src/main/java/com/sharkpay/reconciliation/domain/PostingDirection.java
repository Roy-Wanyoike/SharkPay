package com.sharkpay.reconciliation.domain;

/**
 * Debit or credit side of one compensation leg. Domain-owned twin of the
 * ledger wire's direction (the use case maps to the port's vocabulary).
 */
public enum PostingDirection {

    DEBIT("debit"),
    CREDIT("credit");

    private final String wireName;

    PostingDirection(String wireName) {
        this.wireName = wireName;
    }

    /** The wire/API/DB name. */
    public String wireName() {
        return wireName;
    }

    public PostingDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    /** Parses the wire name (storage/API); never guesses. */
    public static PostingDirection fromWireName(String wireName) {
        for (PostingDirection direction : values()) {
            if (direction.wireName.equals(wireName)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("unknown posting direction: " + wireName);
    }
}
