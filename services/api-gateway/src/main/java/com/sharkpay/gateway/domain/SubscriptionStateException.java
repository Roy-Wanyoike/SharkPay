package com.sharkpay.gateway.domain;

/** A lifecycle transition the endpoint's state does not allow (409 state_conflict). */
public final class SubscriptionStateException extends GatewayDomainException {

    public SubscriptionStateException(String message) {
        super(message);
    }
}
