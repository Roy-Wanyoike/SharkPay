package com.sharkpay.payments.domain;

/**
 * The destination wallet referenced by a create request does not exist
 * (404 {@code not_found} — common.yaml request-body identifier rule).
 */
public class UnknownWalletException extends PaymentDomainException {

    private final String walletId;

    public UnknownWalletException(String walletId) {
        super("Wallet " + walletId + " not found.");
        this.walletId = walletId;
    }

    public String walletId() {
        return walletId;
    }
}
