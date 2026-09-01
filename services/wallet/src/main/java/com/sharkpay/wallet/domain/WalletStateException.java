package com.sharkpay.wallet.domain;

/**
 * A wallet lifecycle transition was attempted from a state that does not
 * allow it (e.g. freezing a frozen wallet, holding funds on a frozen wallet).
 */
public class WalletStateException extends WalletDomainException {

    public WalletStateException(String walletId, WalletStatus status, String attempted) {
        super("wallet " + walletId + " is " + status.wireName() + "; cannot " + attempted);
    }
}
