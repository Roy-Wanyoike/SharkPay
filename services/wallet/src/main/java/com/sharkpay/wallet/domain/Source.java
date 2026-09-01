package com.sharkpay.wallet.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The domain that owns a hold or a ledger journal entry. Values mirror the
 * ledger event schema (contracts/events/ledger.posting.v1.json
 * {@code data.source}) and the wallet event schemas.
 */
public enum Source {

    PAYMENTS("payments"),
    PAYOUTS("payouts"),
    TRANSFERS("transfers"),
    FX("fx"),
    FEES("fees"),
    OPS("ops");

    private final String wireName;

    Source(String wireName) {
        this.wireName = wireName;
    }

    /** The contract wire name (lowercase). */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static Source fromWire(String value) {
        for (Source source : values()) {
            if (source.wireName.equalsIgnoreCase(value == null ? "" : value.trim())) {
                return source;
            }
        }
        throw new IllegalArgumentException("unknown source domain: " + value);
    }
}
