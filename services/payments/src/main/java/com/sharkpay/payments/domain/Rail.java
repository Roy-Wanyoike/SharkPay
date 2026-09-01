package com.sharkpay.payments.domain;

import java.util.List;

/**
 * Payment rail / provider family (contracts/openapi/v1/payments.yaml Rail).
 * {@code rail} on create is a hint; the router makes the final provider
 * choice, but the rail itself is the product-level decision the fee schedule
 * prices.
 */
public enum Rail {

    HONEYCOIN("honeycoin"),
    MPESA("mpesa"),
    BANK("bank"),
    ON_CHAIN("on_chain");

    private static final List<Rail> CANONICAL_ORDER = List.of(HONEYCOIN, MPESA, BANK, ON_CHAIN);

    private final String wireName;

    Rail(String wireName) {
        this.wireName = wireName;
    }

    /** Wire value (payments.yaml enum: honeycoin | mpesa | bank | on_chain). */
    public String wireName() {
        return wireName;
    }

    /** Parses the wire value (case-sensitive; the enum is closed in /v1). */
    public static Rail fromWire(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("rail is required");
        }
        String value = wire.trim();
        for (Rail rail : values()) {
            if (rail.wireName.equals(value)) {
                return rail;
            }
        }
        throw new IllegalArgumentException("unknown rail: " + wire);
    }

    /**
     * Canonical evaluation order used when a create request carries no rail
     * hint: the first rail (in this fixed order) whose fee schedule serves
     * the currency is the default rail — deterministic and replayable.
     */
    public static List<Rail> canonicalOrder() {
        return CANONICAL_ORDER;
    }
}
