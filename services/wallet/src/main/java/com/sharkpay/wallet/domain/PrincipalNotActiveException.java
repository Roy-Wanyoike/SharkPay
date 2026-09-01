package com.sharkpay.wallet.domain;

/**
 * The wallet-owning principal exists but is not ACTIVE (suspended/closed), so
 * wallets cannot be created for it.
 */
public class PrincipalNotActiveException extends WalletDomainException {

    public PrincipalNotActiveException(java.util.UUID principalId, String status) {
        super("principal " + principalId + " is " + status + "; wallets require an active principal");
    }
}
