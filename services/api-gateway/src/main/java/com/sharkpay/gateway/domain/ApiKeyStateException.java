package com.sharkpay.gateway.domain;

/** An API key lifecycle transition the status does not allow (409 state_conflict). */
public final class ApiKeyStateException extends GatewayDomainException {

    public ApiKeyStateException(String message) {
        super(message);
    }
}
