package com.sharkpay.wallet.domain;

/**
 * A second wallet for the same principal and currency was requested — a
 * principal has at most one wallet per currency.
 */
public class DuplicateWalletException extends WalletDomainException {

    public DuplicateWalletException(java.util.UUID principalId, String currency) {
        super("principal " + principalId + " already has a " + currency + " wallet");
    }
}
