package com.sharkpay.money;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The supported currency table. ISO 4217 alpha codes are 3 letters;
 * stablecoin codes (USDC/USDT) use 4. V1 currencies per docs/PRD.md §7 (D2):
 * KES USD EUR GBP USDC USDT.
 * <p>
 * Semantics mirror {@code packages/go/money/currency.go}.
 */
public final class Currencies {

    /** Maps each supported currency code to its minor-unit exponent. */
    private static final Map<String, Integer> TABLE =
        Map.of(
            "KES", 2,
            "USD", 2,
            "EUR", 2,
            "GBP", 2,
            "USDC", 6,
            "USDT", 6);

    private static final Set<String> SUPPORTED =
        Collections.unmodifiableSet(new TreeSet<>(TABLE.keySet()));

    private Currencies() {
    }

    /** Returns the supported currency codes, sorted. */
    public static Set<String> supported() {
        return SUPPORTED;
    }

    /** Reports whether the currency (case-insensitive) is known. */
    public static boolean isSupported(String currency) {
        try {
            exponentFor(currency);
            return true;
        } catch (UnknownCurrencyException e) {
            return false;
        }
    }

    /**
     * Returns the minor-unit exponent of a supported currency
     * (case-insensitive lookup).
     *
     * @throws UnknownCurrencyException for unknown codes
     */
    public static int exponentFor(String currency) {
        String cur = normalize(currency);
        return TABLE.get(cur);
    }

    /**
     * Validates and canonicalises a currency code: surrounding whitespace is
     * trimmed and the code is upper-cased; unknown codes throw.
     *
     * @throws UnknownCurrencyException for unknown codes
     */
    public static String normalize(String currency) {
        if (currency == null) {
            throw new UnknownCurrencyException("null");
        }
        String cur = currency.trim().toUpperCase();
        if (!TABLE.containsKey(cur)) {
            throw new UnknownCurrencyException(currency);
        }
        return cur;
    }
}
