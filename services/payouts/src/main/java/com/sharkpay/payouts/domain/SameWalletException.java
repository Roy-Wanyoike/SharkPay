package com.sharkpay.payouts.domain;

/** Source and destination wallets are identical (422 same_wallet). */
public class SameWalletException extends PayoutsDomainException {

    private final String walletId;

    public SameWalletException(String walletId) {
        super("source and destination wallets must differ: " + walletId);
        this.walletId = walletId;
    }

    public String walletId() {
        return walletId;
    }
}
