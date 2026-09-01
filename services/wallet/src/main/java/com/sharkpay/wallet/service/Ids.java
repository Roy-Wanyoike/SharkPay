package com.sharkpay.wallet.service;

import java.util.UUID;

/**
 * Public id generation for wallets and holds. Ids match the contract
 * patterns {@code ^wal_[0-9A-Za-z]{20,}$} / {@code ^hld_[0-9A-Za-z]{20,}$}
 * (UUID hex, 32 chars, prefixed) and error-envelope request ids
 * {@code ^req_[0-9A-Za-z]+$}.
 */
public final class Ids {

    private Ids() {
    }

    public static String newWalletId() {
        return "wal_" + uuidHex();
    }

    public static String newHoldId() {
        return "hld_" + uuidHex();
    }

    public static String requestId() {
        return "req_" + uuidHex();
    }

    private static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
