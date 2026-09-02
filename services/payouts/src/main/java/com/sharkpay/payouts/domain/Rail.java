package com.sharkpay.payouts.domain;

import java.util.Map;
import java.util.Objects;

/**
 * External payout rail (contracts/openapi/v1/payouts.yaml PayoutRail).
 * Payouts go out (wallet → external), so honeycoin is not a payout
 * destination rail at V1.
 */
public enum Rail {
    MPESA("mpesa"), BANK("bank"), ON_CHAIN("on_chain");

    private final String wireName;

    Rail(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Case-insensitive wire parse ("on_chain" also accepts "onchain"/"on-chain"). */
    public static Rail fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("rail is required");
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        for (Rail rail : values()) {
            if (rail.wireName.equals(normalized)) {
                return rail;
            }
        }
        // documented alias: the underscore-free spelling ("onchain")
        String compact = normalized.replace("_", "");
        for (Rail rail : values()) {
            if (rail.wireName.replace("_", "").equals(compact)) {
                return rail;
            }
        }
        throw new IllegalArgumentException("unknown rail: " + value);
    }

    /** Destination type implied by this rail (contract: must be compatible). */
    public String destinationType() {
        return wireName;
    }

    /**
     * The payout currency compatible with this rail: M-Pesa is the Kenyan
     * mobile-money rail (KES only); bank rails serve the fiat set; on-chain
     * rails serve the stablecoin set (contract example: USDC on Base).
     */
    public boolean supportsCurrency(String currency) {
        return switch (this) {
            case MPESA -> "KES".equals(currency);
            case BANK -> !"USDC".equals(currency) && !"USDT".equals(currency);
            case ON_CHAIN -> "USDC".equals(currency) || "USDT".equals(currency);
        };
    }

    /** Destination-detail fields this rail expects (validation + redaction). */
    public Map<String, String> expectedDetailFields() {
        return switch (this) {
            case MPESA -> Map.of("msisdn", "required");
            case BANK -> Map.of("bank_code", "required", "account_number", "required");
            case ON_CHAIN -> Map.of("network", "required", "address", "required");
        };
    }

    @Override
    public String toString() {
        return wireName;
    }

    /** Null-tolerant valueOf for entity mapping. */
    public static Rail fromName(String name) {
        Objects.requireNonNull(name, "rail name is required");
        return Rail.valueOf(name.trim().toUpperCase());
    }
}
