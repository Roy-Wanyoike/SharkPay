package com.sharkpay.wallet.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Wallet lifecycle (docs/STATE-MACHINES.md §5): {@code active ⇄ frozen}
 * (freeze by compliance only). {@code closed} is a V2 terminal state
 * (zero balances only) and is intentionally not modelled yet.
 */
public enum WalletStatus {

    ACTIVE("active"),
    FROZEN("frozen");

    private final String wireName;

    WalletStatus(String wireName) {
        this.wireName = wireName;
    }

    /** The contract wire name (lowercase, wallets.yaml WalletStatus). */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static WalletStatus fromWire(String value) {
        for (WalletStatus status : values()) {
            if (status.wireName.equalsIgnoreCase(value == null ? "" : value.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown wallet status: " + value);
    }
}
