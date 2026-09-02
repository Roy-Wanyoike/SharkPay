package com.sharkpay.gateway.domain;

/**
 * Base class of the gateway's domain errors so the API layer can keep a
 * fail-safe default mapping (mirrors the wallet service's
 * {@code WalletDomainException}).
 */
public abstract class GatewayDomainException extends RuntimeException {

    protected GatewayDomainException(String message) {
        super(message);
    }
}
