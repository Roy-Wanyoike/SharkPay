package com.sharkpay.payments.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The endpoint of a payment: where the collected funds land. Three shapes
 * (the union is closed for /v1):
 *
 * <ul>
 *   <li>{@link Type#INTERNAL_WALLET} — settle into a SharkPay wallet
 *       ({@code wal_...}); this is what {@code POST /payments} creates
 *       (destination_wallet);</li>
 *   <li>{@link Type#EXTERNAL_RAIL} — an external endpoint addressed by rail
 *       details (msisdn / bank / on-chain address);</li>
 *   <li>{@link Type#FX_QUOTE} — an fx quote reference ({@code fxq_...}) the
 *       conversion executes against once the payment confirms.</li>
 * </ul>
 */
public record Destination(Type type, String walletId, String externalDetails, String fxQuoteRef) {

    /** Public wallet id pattern (contracts/openapi/v1/common-adjacent Money/wal patterns). */
    public static final Pattern WALLET_ID_PATTERN = Pattern.compile("^wal_[0-9A-Za-z]{20,}$");

    /** Fx quote id pattern (contracts/openapi/v1/fx.yaml). */
    public static final Pattern FX_QUOTE_ID_PATTERN = Pattern.compile("^fxq_[0-9A-Za-z]{20,}$");

    public enum Type {
        INTERNAL_WALLET, EXTERNAL_RAIL, FX_QUOTE
    }

    public Destination {
        Objects.requireNonNull(type, "destination type is required");
        switch (type) {
            case INTERNAL_WALLET -> {
                if (walletId == null || !WALLET_ID_PATTERN.matcher(walletId).matches()) {
                    throw new IllegalArgumentException(
                            "destination wallet id must match " + WALLET_ID_PATTERN.pattern() + ": " + walletId);
                }
            }
            case EXTERNAL_RAIL -> {
                if (externalDetails == null || externalDetails.isBlank()) {
                    throw new IllegalArgumentException("external rail destination details are required");
                }
            }
            case FX_QUOTE -> {
                if (fxQuoteRef == null || !FX_QUOTE_ID_PATTERN.matcher(fxQuoteRef).matches()) {
                    throw new IllegalArgumentException(
                            "destination fx quote ref must match " + FX_QUOTE_ID_PATTERN.pattern()
                                    + ": " + fxQuoteRef);
                }
            }
        }
    }

    /** Settles into the internal wallet {@code walletId}. */
    public static Destination internalWallet(String walletId) {
        return new Destination(Type.INTERNAL_WALLET, walletId, null, null);
    }

    /** External rail endpoint ({@code details} is opaque, no secrets). */
    public static Destination externalRail(String details) {
        return new Destination(Type.EXTERNAL_RAIL, null, details, null);
    }

    /** Conversion bound to a locked fx quote. */
    public static Destination fxQuote(String quoteRef) {
        return new Destination(Type.FX_QUOTE, null, null, quoteRef);
    }

    /** The internal wallet id, when this destination is an internal wallet. */
    public Optional<String> internalWalletId() {
        return type == Type.INTERNAL_WALLET ? Optional.of(walletId) : Optional.empty();
    }

    @Override
    public String toString() {
        return switch (type) {
            case INTERNAL_WALLET -> "Destination[internal " + walletId + "]";
            case EXTERNAL_RAIL -> "Destination[external " + externalDetails + "]";
            case FX_QUOTE -> "Destination[fx " + fxQuoteRef + "]";
        };
    }
}
