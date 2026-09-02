package com.sharkpay.payouts.domain;

/** Referenced wallet does not exist (404 — path id or request-body id). */
public class UnknownWalletException extends PayoutsDomainException {

    private final String walletId;

    public UnknownWalletException(String walletId) {
        super("wallet " + walletId + " not found");
        this.walletId = walletId;
    }

    public String walletId() {
        return walletId;
    }
}
