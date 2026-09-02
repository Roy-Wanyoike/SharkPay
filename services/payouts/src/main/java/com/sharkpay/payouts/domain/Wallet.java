package com.sharkpay.payouts.domain;

import java.util.regex.Pattern;

/**
 * Wallet identifier reference (wallets are owned by the wallet service; the
 * payouts domain only validates the shape of the ids it carries). Pattern
 * per contracts/openapi/v1/common.yaml-wired wallet references:
 * {@code ^wal_[0-9A-Za-z]{20,}$}.
 */
public final class Wallet {

    /** Public wallet id pattern (contracts/openapi/v1/wallets.yaml). */
    public static final Pattern ID_PATTERN = Pattern.compile("^wal_[0-9A-Za-z]{20,}$");

    private Wallet() {
    }
}
