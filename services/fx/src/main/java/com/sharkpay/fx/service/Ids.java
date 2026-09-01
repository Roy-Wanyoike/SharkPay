package com.sharkpay.fx.service;

import java.util.UUID;

/**
 * Public id generation for quotes and conversions. Ids match the contract
 * patterns {@code ^fxq_[0-9A-Za-z]{20,}$} / {@code ^cnv_[0-9A-Za-z]{20,}$}
 * (UUID hex, 32 chars, prefixed) and error-envelope request ids
 * {@code ^req_[0-9A-Za-z]+$}.
 */
public final class Ids {

    private Ids() {
    }

    public static String newQuoteId() {
        return "fxq_" + uuidHex();
    }

    public static String newConversionId() {
        return "cnv_" + uuidHex();
    }

    public static String requestId() {
        return "req_" + uuidHex();
    }

    private static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
