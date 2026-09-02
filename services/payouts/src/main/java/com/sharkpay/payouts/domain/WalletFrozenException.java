package com.sharkpay.payouts.domain;

/** Referenced wallet is frozen; money cannot move (422 wallet_frozen). */
public class WalletFrozenException extends PayoutsDomainException {

    private final String walletId;

    public WalletFrozenException(String walletId) {
        super("wallet " + walletId + " is frozen");
        this.walletId = walletId;
    }

    public String walletId() {
        return walletId;
    }
}
